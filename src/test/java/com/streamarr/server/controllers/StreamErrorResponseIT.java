package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.support.AuthTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Runs against the embedded server because MockMvc never performs the servlet container's error
 * dispatch, which is where a stream request's failure is answered.
 */
@Tag("IntegrationTest")
@DisplayName("Stream Error Response Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamErrorResponseIT extends AbstractIntegrationTest {

  private static final String FAILURE_DETAIL =
      "worker connection 7 failed: direct memory exhausted";
  private static final FakeStreamingService STUB_SERVICE = new FakeStreamingService();
  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;

  @TestBean StreamingService streamingService;
  @TestBean SegmentStore segmentStore;
  @TestBean TranscodeExecutor transcodeExecutor;

  private AuthTestSupport.TestIdentity identity;

  static StreamingService streamingService() {
    return STUB_SERVICE;
  }

  static SegmentStore segmentStore() {
    return new UnreachableSegmentStore();
  }

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  @BeforeEach
  void seedIdentity() {
    identity = authTestSupport.createIdentity();
  }

  @AfterEach
  void deleteIdentity() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should answer a server error when media segment delivery fails unexpectedly")
  void shouldAnswerServerErrorWhenMediaSegmentDeliveryFailsUnexpectedly() throws Exception {
    var session = StreamSessionFixture.buildActiveSession();
    STUB_SERVICE.addSession(session);
    var token = playbackToken(session.getSessionId());

    var response = get("/api/stream/%s/segment0.m4s".formatted(session.getSessionId()), token);

    assertServerErrorWithoutDetail(response, token);
  }

  @Test
  @DisplayName(
      "Should answer a server error when initialization segment delivery fails unexpectedly")
  void shouldAnswerServerErrorWhenInitializationSegmentDeliveryFailsUnexpectedly()
      throws Exception {
    var session = StreamSessionFixture.buildActiveSession();
    STUB_SERVICE.addSession(session);
    var token = playbackToken(session.getSessionId());

    var response = get("/api/stream/%s/init.mp4".formatted(session.getSessionId()), token);

    assertServerErrorWithoutDetail(response, token);
  }

  @Test
  @DisplayName("Should answer a server error when a playlist request fails unexpectedly")
  void shouldAnswerServerErrorWhenPlaylistRequestFailsUnexpectedly() throws Exception {
    var sessionId = UUID.randomUUID();
    STUB_SERVICE.failAccess(sessionId, new IllegalStateException(FAILURE_DETAIL));
    var token = playbackToken(sessionId);

    var response = get("/api/stream/%s/multivariant.m3u8".formatted(sessionId), token);

    assertServerErrorWithoutDetail(response, token);
  }

  @Test
  @DisplayName("Should answer not found when a playback token requests an unmapped stream path")
  void shouldAnswerNotFoundWhenPlaybackTokenRequestsUnmappedStreamPath() throws Exception {
    var session = StreamSessionFixture.buildActiveSession();
    STUB_SERVICE.addSession(session);

    var response =
        get(
            "/api/stream/%s/master.m3u8".formatted(session.getSessionId()),
            playbackToken(session.getSessionId()));

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("Should refuse the error page when a playback token requests it directly")
  void shouldRefuseErrorPageWhenPlaybackTokenRequestsItDirectly() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:%d/error".formatted(port)))
            .header("Authorization", "Bearer " + playbackToken(UUID.randomUUID()))
            .GET()
            .build();

    var response = send(request);

    assertThat(response.statusCode()).isEqualTo(403);
  }

  private String playbackToken(UUID streamSessionId) {
    return authTestSupport.playbackBearer(identity, streamSessionId);
  }

  private HttpResponse<String> get(String path, String playbackToken) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:%d%s?t=%s".formatted(port, path, playbackToken)))
            .GET()
            .build();

    return send(request);
  }

  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private static void assertServerErrorWithoutDetail(
      HttpResponse<String> response, String playbackToken) {
    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body())
        .doesNotContain(FAILURE_DETAIL)
        .doesNotContain(IllegalStateException.class.getName())
        .doesNotContain("trace")
        .doesNotContain(playbackToken);
  }

  /** A segment store whose backing connection fails with an exception delivery does not expect. */
  private static final class UnreachableSegmentStore implements SegmentStore {

    @Override
    public byte[] readSegment(UUID sessionId, String segmentName) {
      throw new IllegalStateException(FAILURE_DETAIL);
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      throw new IllegalStateException(FAILURE_DETAIL);
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      throw new IllegalStateException(FAILURE_DETAIL);
    }

    @Override
    public void deleteSession(UUID sessionId) {
      // Nothing is stored.
    }
  }
}
