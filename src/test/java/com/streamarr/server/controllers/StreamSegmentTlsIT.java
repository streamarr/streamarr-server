package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.support.AuthTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Drives the segment endpoint over a connection on which Tomcat itself terminates TLS. Tomcat still
 * accepts a segment file to send there, and encrypts the file's bytes as it copies them.
 */
@Tag("IntegrationTest")
@DisplayName("Stream Segment TLS Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamSegmentTlsIT extends AbstractIntegrationTest {

  // Longer than the socket buffers, so one answer needs several transfers.
  private static final int SEGMENT_LENGTH = 3 * 1024 * 1024;
  private static final String KEY_STORE_PASSWORD = "segment-tls-test";
  private static final Path KEY_STORE = createServerKeyStore();
  private static final FakeStreamingService STUB_SERVICE = new FakeStreamingService();
  private static final LocalSegmentStore STORE =
      new LocalSegmentStore(KEY_STORE.resolveSibling("segments"));

  @LocalServerPort private int port;
  @Autowired private AuthTestSupport authTestSupport;

  @TestBean StreamingService streamingService;
  @TestBean SegmentStore segmentStore;
  @TestBean TranscodeExecutor transcodeExecutor;

  private AuthTestSupport.TestIdentity identity;
  private StreamSession session;
  private byte[] segmentBytes;

  static StreamingService streamingService() {
    return STUB_SERVICE;
  }

  static SegmentStore segmentStore() {
    return STORE;
  }

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  @DynamicPropertySource
  static void terminateTlsInTomcat(DynamicPropertyRegistry registry) {
    registry.add("server.ssl.key-store", () -> KEY_STORE.toUri().toString());
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-store-password", () -> KEY_STORE_PASSWORD);
  }

  @BeforeEach
  void storeSegment() {
    identity = authTestSupport.createIdentity();
    session = StreamSessionFixture.sessionWithDurationBuilder(7200).build();
    session.setHandle(StreamSessionFixture.mintHandle(1L, TranscodeStatus.ACTIVE));
    STUB_SERVICE.addSession(session);
    segmentBytes = new byte[SEGMENT_LENGTH];
    for (var index = 0; index < SEGMENT_LENGTH; index++) {
      segmentBytes[index] = (byte) (index % 251);
    }
    STORE.storeSegment(session.getSessionId(), "segment0.m4s", segmentBytes);
  }

  @AfterEach
  void deleteStoredSession() {
    STORE.deleteSession(session.getSessionId());
    authTestSupport.deleteIdentity(identity);
  }

  @AfterAll
  static void deleteSegmentFolder() {
    STORE.shutdown();
  }

  @Test
  @DisplayName("Should answer the whole segment with its exact bytes when Tomcat terminates TLS")
  void shouldAnswerWholeSegmentWithItsExactBytesWhenTomcatTerminatesTls() throws Exception {
    var answer = send(segmentRequest().build());

    assertThat(answer.sslSession()).isPresent();
    assertThat(answer.statusCode()).isEqualTo(200);
    assertThat(answer.headers().firstValue("Content-Length"))
        .hasValue(String.valueOf(SEGMENT_LENGTH));
    assertThat(answer.body()).isEqualTo(segmentBytes);
  }

  @Test
  @DisplayName("Should answer one byte range with its exact bytes when Tomcat terminates TLS")
  void shouldAnswerOneByteRangeWithItsExactBytesWhenTomcatTerminatesTls() throws Exception {
    var answer = send(segmentRequest().header("Range", "bytes=100000-2099999").build());

    assertThat(answer.sslSession()).isPresent();
    assertThat(answer.statusCode()).isEqualTo(206);
    assertThat(answer.headers().firstValue("Content-Range"))
        .hasValue("bytes 100000-2099999/" + SEGMENT_LENGTH);
    assertThat(answer.body()).isEqualTo(Arrays.copyOfRange(segmentBytes, 100000, 2100000));
  }

  private HttpRequest.Builder segmentRequest() {
    return HttpRequest.newBuilder(
        URI.create(
            "https://localhost:%d/api/stream/%s/segment0.m4s?t=%s"
                .formatted(
                    port,
                    session.getSessionId(),
                    authTestSupport.playbackBearer(identity, session.getSessionId()))));
  }

  private static HttpResponse<byte[]> send(HttpRequest request)
      throws IOException, InterruptedException, GeneralSecurityException {
    try (var client = HttpClient.newBuilder().sslContext(trustingServerKeyStore()).build()) {
      return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  private static SSLContext trustingServerKeyStore() throws IOException, GeneralSecurityException {
    var trusted = KeyStore.getInstance(KEY_STORE.toFile(), KEY_STORE_PASSWORD.toCharArray());
    var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trusted);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers.getTrustManagers(), null);
    return context;
  }

  /** A self-signed certificate for {@code localhost}, made by the running JDK's keytool. */
  private static Path createServerKeyStore() {
    try {
      var keyStore = Files.createTempDirectory("stream-segment-tls").resolve("server.p12");
      var keytool =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "server",
                  "-keyalg",
                  "EC",
                  "-groupname",
                  "secp256r1",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=dns:localhost",
                  "-validity",
                  "2",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  keyStore.toString(),
                  "-storepass",
                  KEY_STORE_PASSWORD)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
      assertThat(keytool.exitValue()).isZero();
      return keyStore;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while creating the TLS test key store", e);
    }
  }
}
