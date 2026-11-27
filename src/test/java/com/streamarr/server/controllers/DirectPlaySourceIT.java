package com.streamarr.server.controllers;

import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.support.AuthTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

/**
 * Drives the direct-play source endpoint on a real port, so each case shows what Tomcat sends:
 * status, headers and the exact body bytes (RFC 9110 sections 14 and 15.5.17).
 */
@Tag("IntegrationTest")
@DisplayName("Direct Play Source Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "streaming.remote.source-root=${java.io.tmpdir}")
class DirectPlaySourceIT extends AbstractIntegrationTest {

  private static final int SOURCE_LENGTH = 1000;
  private static final long LARGE_SOURCE_LENGTH = 256L << 20;
  private static final String OPENED_FILE_MARKER = "opened-file-head";
  private static final String SWAPPED_FILE_MARKER = "swapped-in-head!";

  @LocalServerPort private int port;

  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;
  @Autowired private JwtDecoder jwtDecoder;

  @TempDir private Path directory;

  private final HttpClient client = HttpClient.newHttpClient();
  private AuthTestSupport.TestIdentity identity;
  private byte[] sourceBytes;
  private StreamSession streamSession;
  private String token;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    sourceBytes = new byte[SOURCE_LENGTH];
    for (var index = 0; index < SOURCE_LENGTH; index++) {
      sourceBytes[index] = (byte) (index % 251);
    }
  }

  @AfterEach
  void tearDown() {
    runtimeRegistry.removeById(streamSession.getSessionId());
    authTestSupport.deleteIdentity(identity);
    client.close();
  }

  @Test
  @DisplayName(
      "Should answer the bounded range with 206 and its exact bytes when a range is closed")
  void shouldAnswerBoundedRangeWith206AndItsExactBytesWhenRangeIsClosed() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(get("source.mp4").header("Range", "bytes=0-99"));

    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Range")).hasValue("bytes 0-99/1000");
    assertThat(response.headers().firstValue("Content-Length")).hasValue("100");
    assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
    assertThat(response.body()).isEqualTo(Arrays.copyOfRange(sourceBytes, 0, 100));
  }

  @Test
  @DisplayName("Should answer through the last byte when a range is open-ended")
  void shouldAnswerThroughLastByteWhenRangeIsOpenEnded() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(get("source.mp4").header("Range", "bytes=990-"));

    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Range")).hasValue("bytes 990-999/1000");
    assertThat(response.headers().firstValue("Content-Length")).hasValue("10");
    assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
    assertThat(response.body()).isEqualTo(Arrays.copyOfRange(sourceBytes, 990, 1000));
  }

  @Test
  @DisplayName("Should answer the final bytes when a range is a suffix")
  void shouldAnswerFinalBytesWhenRangeIsSuffix() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(get("source.mp4").header("Range", "bytes=-10"));

    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Range")).hasValue("bytes 990-999/1000");
    assertThat(response.headers().firstValue("Content-Length")).hasValue("10");
    assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
    assertThat(response.body()).isEqualTo(Arrays.copyOfRange(sourceBytes, 990, 1000));
  }

  @Test
  @DisplayName("Should answer 416 with the length and no body when a range starts past the end")
  void shouldAnswer416WithTheLengthAndNoBodyWhenRangeStartsPastTheEnd() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(get("source.mp4").header("Range", "bytes=1000-"));

    assertThat(response.statusCode()).isEqualTo(416);
    assertThat(response.headers().firstValue("Content-Range")).hasValue("bytes */1000");
    assertThat(response.headers().firstValue("Content-Length")).hasValue("0");
    assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
    assertThat(response.body()).isEmpty();
  }

  @Test
  @DisplayName("Should answer HEAD with the headers alone when a player asks for the length")
  void shouldAnswerHeadWithTheHeadersAloneWhenPlayerAsksForTheLength() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(request("source.mp4").method("HEAD", HttpRequest.BodyPublishers.noBody()));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Length")).hasValue("1000");
    assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
    assertThat(response.headers().firstValue("Content-Type")).hasValue("video/mp4");
    assertThat(response.body()).isEmpty();
  }

  @Test
  @DisplayName("Should answer HEAD without reading the file when the file is large")
  void shouldAnswerHeadWithoutReadingTheFileWhenTheFileIsLarge() throws Exception {
    startDirectPlay(SourceContainer.MP4);
    var sparseLength = 1L << 40;
    try (var file = new RandomAccessFile(streamSession.getSourcePath().toFile(), "rw")) {
      file.setLength(sparseLength);
    }

    // Reading a sparse 1 TiB file takes minutes, so a HEAD that reads the body misses this bound.
    var response =
        send(
            request("source.mp4")
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Length"))
        .hasValue(String.valueOf(sparseLength));
  }

  @Test
  @DisplayName("Should answer the whole file with 200 when a request has no range")
  void shouldAnswerWholeFileWith200WhenRequestHasNoRange() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(get("source.mp4"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Length")).hasValue("1000");
    assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
    assertThat(response.body()).isEqualTo(sourceBytes);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SourceContainer.class)
  @DisplayName("Should label the source with its container's media type when the file name differs")
  void shouldLabelSourceWithItsContainersMediaTypeWhenFileNameDiffers(SourceContainer container)
      throws Exception {
    startDirectPlay(container);

    var response = send(get("source." + container.getExtension()).header("Range", "bytes=0-0"));

    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Type")).hasValue(container.getMediaType());
  }

  @Test
  @DisplayName("Should label each part with the container's media type when a request has ranges")
  void shouldLabelEachPartWithTheContainersMediaTypeWhenRequestHasRanges() throws Exception {
    startDirectPlay(SourceContainer.MOV);

    var response = send(get("source.mov").header("Range", "bytes=0-9,20-29"));

    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("multipart/byteranges"));
    assertThat(new String(response.body(), StandardCharsets.ISO_8859_1))
        .contains("Content-Type: video/quicktime")
        .doesNotContain("video/x-matroska");
  }

  @Test
  @DisplayName("Should answer 404 when the URL names another container's extension")
  void shouldAnswer404WhenUrlNamesAnotherContainersExtension() throws Exception {
    startDirectPlay(SourceContainer.MP4);

    var response = send(get("source.mov").header("Range", "bytes=0-99"));

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("Should answer 404 when the source file is gone")
  void shouldAnswer404WhenSourceFileIsGone() throws Exception {
    startDirectPlay(SourceContainer.MP4);
    Files.delete(streamSession.getSourcePath());

    var response = send(get("source.mp4").header("Range", "bytes=0-99"));

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("Should answer 404 when a folder on the source path is a soft link")
  void shouldAnswer404WhenFolderOnSourcePathIsSoftLink() throws Exception {
    var show = Files.createDirectory(directory.resolve("show"));
    Files.write(show.resolve("movie.mkv"), sourceBytes);
    var linkedShow = Files.createSymbolicLink(directory.resolve("linked-show"), show);
    startDirectPlay(linkedShow.resolve("movie.mkv"), SourceContainer.MP4);

    var response = send(get("source.mp4").header("Range", "bytes=0-99"));

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "Should keep serving the opened file when a folder on its path becomes a soft link mid-answer")
  void shouldKeepServingTheOpenedFileWhenFolderOnItsPathBecomesSoftLinkMidAnswer()
      throws Exception {
    var show = Files.createDirectory(directory.resolve("show"));
    var otherShow = Files.createDirectory(directory.resolve("other-show"));
    var source = largeSparseSource(show.resolve("movie.mkv"), OPENED_FILE_MARKER);
    largeSparseSource(otherShow.resolve("movie.mkv"), SWAPPED_FILE_MARKER);
    startDirectPlay(source, SourceContainer.MP4);
    // The first range is far larger than every socket buffer, so the server stays inside it until
    // the client reads on. The second range starts before the first, so serving it by opening the
    // file's name again would open whatever the name leads to after the swap.
    var ranges =
        "bytes=%d-%d,0-%d"
            .formatted(
                LARGE_SOURCE_LENGTH / 2, LARGE_SOURCE_LENGTH - 1, OPENED_FILE_MARKER.length() - 1);

    var response =
        client.send(
            get("source.mp4").header("Range", ranges).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      assertThat(body.readNBytes(1024)).hasSize(1024);
      Files.move(show, directory.resolve("show-before-swap"));
      Files.createSymbolicLink(show, otherShow);

      assertThat(response.statusCode()).isEqualTo(206);
      assertThat(lastBytesOf(body))
          .contains(OPENED_FILE_MARKER)
          .doesNotContain(SWAPPED_FILE_MARKER);
    }
  }

  @Test
  @DisplayName(
      "Should close the connection short of the promised length when the file shrinks mid-answer")
  void shouldCloseConnectionShortOfPromisedLengthWhenFileShrinksMidAnswer() throws Exception {
    var source = largeSparseSource(directory.resolve("movie.mkv"), OPENED_FILE_MARKER);
    startDirectPlay(source, SourceContainer.MP4);

    var response =
        client.send(get("source.mp4").build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var reader = Executors.newVirtualThreadPerTaskExecutor();
        var body = response.body()) {
      assertThat(body.readNBytes(1024)).hasSize(1024);
      // The file is far larger than every socket buffer, so the server is still short of the new
      // end when the file shrinks.
      shrink(source, LARGE_SOURCE_LENGTH / 2);

      var restOfBody = reader.submit(() -> body.transferTo(OutputStream.nullOutputStream()));

      assertThat(response.headers().firstValue("Content-Length"))
          .hasValue(String.valueOf(LARGE_SOURCE_LENGTH));
      assertThat(restOfBody)
          .failsWithin(Duration.ofSeconds(30))
          .withThrowableThat()
          .withCauseInstanceOf(IOException.class);
    }
  }

  private static void shrink(Path path, long length) throws IOException {
    try (var file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength(length);
    }
  }

  private static Path largeSparseSource(Path path, String headMarker) throws IOException {
    try (var file = new RandomAccessFile(path.toFile(), "rw")) {
      file.write(headMarker.getBytes(StandardCharsets.ISO_8859_1));
      file.setLength(LARGE_SOURCE_LENGTH);
    }

    return path;
  }

  // The second range and the closing boundary end the body, so its last few kilobytes hold them.
  private static String lastBytesOf(InputStream body) throws IOException {
    var keep = 4096;
    var buffer = new byte[64 * 1024];
    var last = new byte[0];
    for (var count = body.read(buffer); count != -1; count = body.read(buffer)) {
      var joined = Arrays.copyOf(last, last.length + count);
      System.arraycopy(buffer, 0, joined, last.length, count);
      last = Arrays.copyOfRange(joined, Math.max(0, joined.length - keep), joined.length);
    }

    return new String(last, StandardCharsets.ISO_8859_1);
  }

  // The file name names Matroska, so a media type taken from the name would show.
  private void startDirectPlay(SourceContainer container) throws IOException {
    startDirectPlay(Files.write(directory.resolve("movie.mkv"), sourceBytes), container);
  }

  private void startDirectPlay(Path source, SourceContainer container) {
    var authenticatedIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(identity)));
    streamSession =
        directPlaySessionBuilder()
            .authority(authenticatedIdentity.playbackAuthority())
            .sourcePath(source)
            .sourceContainer(container)
            .build();
    runtimeRegistry.save(streamSession);
    token =
        playbackTokenIssuer
            .issue(authenticatedIdentity, streamSession, Duration.ofHours(1))
            .value();
  }

  private HttpRequest.Builder get(String resource) {
    return request(resource).GET();
  }

  private HttpRequest.Builder request(String resource) {
    return HttpRequest.newBuilder(
        URI.create(
            "http://localhost:%d/api/stream/%s/%s?t=%s"
                .formatted(port, streamSession.getSessionId(), resource, token)));
  }

  private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }
}
