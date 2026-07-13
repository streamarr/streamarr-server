package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Stream Controller Integration Tests")
class StreamControllerIT extends AbstractIntegrationTest {

  private static final StubStreamingService STUB_SERVICE = new StubStreamingService();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();
  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();

  @Autowired private MockMvc mockMvc;

  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;
  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private JwtDecoder jwtDecoder;

  private AuthTestSupport.TestIdentity identity;

  @org.junit.jupiter.api.BeforeEach
  void seedIdentity() {
    identity = authTestSupport.createIdentity();
  }

  @org.junit.jupiter.api.AfterEach
  void deleteIdentity() {
    authTestSupport.deleteIdentity(identity);
  }

  private String playbackToken(java.util.UUID streamSessionId) {
    var authenticatedIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(identity)));
    // Minted against a session the caller owns — the issuer refuses anything else.
    var ownedSession =
        StreamSessionFixture.defaultSessionBuilder()
            .sessionId(streamSessionId)
            .authority(authenticatedIdentity.playbackAuthority())
            .build();
    return playbackTokenIssuer
        .issue(authenticatedIdentity, ownedSession, Duration.ofHours(1))
        .value();
  }

  @TestBean StreamingService streamingService;
  @TestBean SegmentStore segmentStore;
  @TestBean TranscodeExecutor transcodeExecutor;

  static StreamingService streamingService() {
    return STUB_SERVICE;
  }

  static SegmentStore segmentStore() {
    return FAKE_SEGMENT_STORE;
  }

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  @Test
  @DisplayName("Should return master playlist with correct content type when session exists")
  void shouldReturnMasterPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    STUB_SERVICE.addSession(session);

    var result =
        mockMvc
            .perform(
                get("/api/stream/{id}/master.m3u8", session.getSessionId())
                    .param("t", playbackToken(session.getSessionId())))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
  }

  @Test
  @DisplayName("Should return 404 when session not found")
  void shouldReturn404WhenSessionNotFound() throws Exception {
    var missingSessionId = UUID.randomUUID();
    mockMvc
        .perform(
            get("/api/stream/{id}/master.m3u8", missingSessionId)
                .param("t", playbackToken(missingSessionId)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve TS segment with correct content type when segment is available")
  void shouldServeTsSegmentWithCorrectContentTypeWhenSegmentIsAvailable() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    var segmentData = new byte[] {0x47, 0x00, 0x11, 0x10};
    STUB_SERVICE.addSession(session);
    FAKE_SEGMENT_STORE.addSegment(session.getSessionId(), "segment0.ts", segmentData);

    var result =
        mockMvc
            .perform(
                get("/api/stream/{id}/segment0.ts", session.getSessionId())
                    .param("t", playbackToken(session.getSessionId())))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp2t");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should return 404 when segment unavailable")
  void shouldReturn404WhenSegmentUnavailable() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    STUB_SERVICE.addSession(session);

    mockMvc
        .perform(
            get("/api/stream/{id}/segment99.ts", session.getSessionId())
                .param("t", playbackToken(session.getSessionId())))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should reject segment request when token missing")
  void shouldRejectSegmentRequestWhenTokenMissing() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    STUB_SERVICE.addSession(session);

    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", session.getSessionId()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should reject segment request when token blank")
  void shouldRejectSegmentRequestWhenTokenBlank() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    STUB_SERVICE.addSession(session);

    // An empty ?t= is no credential at all — it must not reach the decoder as one.
    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", session.getSessionId()).param("t", ""))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should reject playback when token session mismatches path")
  void shouldRejectPlaybackWhenTokenSessionMismatchesPath() throws Exception {
    var sessionA = StreamSessionFixture.buildMpegtsSession();
    var sessionB = StreamSessionFixture.buildMpegtsSession();
    STUB_SERVICE.addSession(sessionA);
    STUB_SERVICE.addSession(sessionB);

    // A captured URL is worth exactly one stream session — never a different one.
    mockMvc
        .perform(
            get("/api/stream/{id}/master.m3u8", sessionB.getSessionId())
                .param("t", playbackToken(sessionA.getSessionId())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should serve segment when access cookie expired but playback token valid")
  void shouldServeSegmentWhenAccessCookieExpiredButPlaybackTokenValid() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    var segmentData = new byte[] {0x47, 0x00, 0x11, 0x10};
    STUB_SERVICE.addSession(session);
    FAKE_SEGMENT_STORE.addSegment(session.getSessionId(), "segment0.ts", segmentData);

    // Browsers attach the stale Path=/ access cookie to every segment fetch; stream paths must
    // ignore headers and cookies entirely or playback dies mid-movie.
    mockMvc
        .perform(
            get("/api/stream/{id}/segment0.ts", session.getSessionId())
                .param("t", playbackToken(session.getSessionId()))
                .cookie(
                    new Cookie("streamarr_access", authTestSupport.expiredProfileBearer(identity))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should return 400 when segment name contains parent directory traversal")
  void shouldReturn400WhenSegmentNameContainsParentDirectoryTraversal() throws Exception {
    // A valid playback token gets past the token filter so the name validation itself answers.
    var sessionId = UUID.randomUUID();
    mockMvc
        .perform(
            get("/api/stream/{id}/{segment}", sessionId, "..secret.ts")
                .param("t", playbackToken(sessionId)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should return 400 when segment name contains backslash")
  void shouldReturn400WhenSegmentNameContainsBackslash() throws Exception {
    mockMvc
        .perform(get("/api/stream/{id}/{segment}", UUID.randomUUID(), "evil\\name.ts"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should return 400 when variant label contains parent directory traversal")
  void shouldReturn400WhenVariantLabelContainsParentDirectoryTraversal() throws Exception {
    mockMvc
        .perform(get("/api/stream/{id}/{variant}/stream.m3u8", UUID.randomUUID(), ".."))
        .andExpect(status().isBadRequest());
  }

  private static class StubStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    @Override
    public StreamSession createSession(CreateStreamSessionCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return Optional.ofNullable(sessions.get(request.streamSessionId()));
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return Collections.unmodifiableCollection(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      // no-op for test fake
    }
  }
}
