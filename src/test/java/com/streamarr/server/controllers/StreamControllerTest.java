package com.streamarr.server.controllers;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.withActiveVariantHandles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.StreamingService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("UnitTest")
@DisplayName("Stream Controller Tests")
class StreamControllerTest {

  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final String VALIDATED_TOKEN = "validated-context-token";
  private static final String SPOOFED_PARAM = "spoofed<script>-param";

  private MockMvc mockMvc;
  private final AtomicReference<UUID> boundStreamSession = new AtomicReference<>(SESSION_ID);
  private StubStreamingService streamingService;
  private FakeSegmentStore segmentStore;
  private HlsPlaylistService playlistService;
  private StreamController controller;

  @BeforeEach
  void setUp() {
    streamingService = new StubStreamingService();
    segmentStore = new FakeSegmentStore();
    playlistService =
        new HlsPlaylistService(
            StreamingProperties.builder()
                .maxConcurrentTranscodes(8)
                .targetSegmentDuration(Duration.ofSeconds(6))
                .sessionTimeout(Duration.ofSeconds(60))
                .build());
    boundStreamSession.set(SESSION_ID);
    controller =
        new StreamController(
            streamingService, playlistService, segmentStore, new BoundAuthorizationService());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  @DisplayName("Should return master playlist with correct content type when session exists")
  void shouldReturnMasterPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    var result =
        mockMvc
            .perform(
                get("/api/stream/{sessionId}/master.m3u8", SESSION_ID).param("t", "unit-token"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-STREAM-INF:");
  }

  @ParameterizedTest
  @ValueSource(strings = {"master.m3u8", "stream.m3u8"})
  @DisplayName(
      "Should embed the validated token in playlists when the request parameter is spoofed")
  void shouldEmbedValidatedTokenInPlaylistsWhenRequestParameterIsSpoofed(String path)
      throws Exception {
    streamingService.setSession(buildMpegtsSession());

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/" + path, SESSION_ID).param("t", SPOOFED_PARAM))
            .andExpect(status().isOk())
            .andReturn();

    assertPlaylistEmbedsValidatedToken(result.getResponse().getContentAsString());
  }

  @Test
  @DisplayName(
      "Should embed the validated token in variant playlists when the request parameter is spoofed")
  void shouldEmbedValidatedTokenInVariantPlaylistsWhenRequestParameterIsSpoofed() throws Exception {
    streamingService.setSession(buildAbrSession());

    var result =
        mockMvc
            .perform(
                get("/api/stream/{sessionId}/{variantLabel}/stream.m3u8", SESSION_ID, "720p")
                    .param("t", SPOOFED_PARAM))
            .andExpect(status().isOk())
            .andReturn();

    assertPlaylistEmbedsValidatedToken(result.getResponse().getContentAsString());
  }

  private static void assertPlaylistEmbedsValidatedToken(String body) {
    assertThat(body).contains("?t=" + VALIDATED_TOKEN);
    assertThat(body).doesNotContain(SPOOFED_PARAM);
  }

  @ParameterizedTest
  @ValueSource(strings = {"master.m3u8", "stream.m3u8", "segment0.ts", "init.mp4"})
  @DisplayName("Should reject stream request when token is bound to another stream session")
  void shouldRejectStreamRequestWhenTokenIsBoundToAnotherStreamSession(String path) {
    streamingService.setSession(buildMpegtsSession());
    boundStreamSession.set(UUID.randomUUID());

    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    get("/api/stream/{sessionId}/" + path, SESSION_ID).param("t", "unit-token")))
        .hasCauseInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"master.m3u8", "stream.m3u8", "segment0.ts", "init.mp4"})
  @DisplayName("Should return 404 when session not found")
  void shouldReturn404WhenSessionNotFound(String path) throws Exception {
    var missingId = UUID.randomUUID();
    boundStreamSession.set(missingId);
    mockMvc
        .perform(get("/api/stream/{sessionId}/" + path, missingId).param("t", "unit-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return media playlist with correct content type when session exists")
  void shouldReturnMediaPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    var result =
        mockMvc
            .perform(
                get("/api/stream/{sessionId}/stream.m3u8", SESSION_ID).param("t", "unit-token"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-TARGETDURATION:");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-ENDLIST");
  }

  @Test
  @DisplayName("Should serve TS segment with correct content type when segment is available")
  void shouldServeTsSegmentWithCorrectContentTypeWhenSegmentIsAvailable() throws Exception {
    streamingService.setSession(buildMpegtsSession());
    var segmentData = new byte[] {0x47, 0x00, 0x11, 0x10};
    segmentStore.addSegment(SESSION_ID, "segment0.ts", segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/segment0.ts", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp2t");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should serve m4s segment with correct content type when segment is available")
  void shouldServeM4sSegmentWithCorrectContentTypeWhenSegmentIsAvailable() throws Exception {
    streamingService.setSession(buildFmp4Session());
    var segmentData = new byte[] {0x00, 0x00, 0x00, 0x1C};
    segmentStore.addSegment(SESSION_ID, "segment0.m4s", segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/segment0.m4s", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should return 404 when segment not ready within timeout")
  void shouldReturn404WhenSegmentNotReadyWithinTimeout() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/segment0.ts", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve init segment when session uses fMP4")
  void shouldServeInitSegmentWhenSessionUsesFmp4() throws Exception {
    streamingService.setSession(buildFmp4Session());
    var initData = new byte[] {0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70};
    segmentStore.addSegment(SESSION_ID, "init.mp4", initData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/init.mp4", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(initData);
  }

  @Test
  @DisplayName("Should return 404 for init segment when session is MPEGTS")
  void shouldReturn404ForInitSegmentWhenSessionIsMpegts() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/init.mp4", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  private StreamSession buildMpegtsSession() {
    return defaultSessionBuilder().sessionId(SESSION_ID).build();
  }

  private StreamSession buildFmp4Session() {
    return defaultSessionBuilder()
        .sessionId(SESSION_ID)
        .mediaProbe(defaultProbeBuilder().videoCodec("hevc").build())
        .transcodeDecision(fullTranscodeDecision("av1", ContainerFormat.FMP4))
        .options(StreamingOptions.builder().supportedCodecs(List.of("av1")).build())
        .build();
  }

  // --- Variant routing tests ---

  private StreamSession buildAbrFmp4Session() {
    var session =
        defaultSessionBuilder()
            .sessionId(SESSION_ID)
            .mediaProbe(defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000).build())
            .transcodeDecision(fullTranscodeDecision("av1", ContainerFormat.FMP4))
            .options(StreamingOptions.builder().supportedCodecs(List.of("av1")).build())
            .variants(
                List.of(
                    defaultVariantBuilder()
                        .width(1920)
                        .height(1080)
                        .videoBitrate(5_000_000L)
                        .label("1080p")
                        .build()))
            .build();
    return withActiveVariantHandles(session);
  }

  private StreamSession buildAbrSession() {
    var session =
        defaultSessionBuilder()
            .sessionId(SESSION_ID)
            .mediaProbe(defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000).build())
            .transcodeDecision(fullTranscodeDecision("h264", ContainerFormat.MPEGTS))
            .variants(
                List.of(
                    defaultVariantBuilder()
                        .width(1920)
                        .height(1080)
                        .videoBitrate(5_000_000L)
                        .label("1080p")
                        .build(),
                    defaultVariantBuilder()
                        .width(1280)
                        .height(720)
                        .videoBitrate(3_000_000L)
                        .label("720p")
                        .build()))
            .build();
    return withActiveVariantHandles(session);
  }

  @Test
  @DisplayName("Should serve variant media playlist when variant exists")
  void shouldServeVariantMediaPlaylistWhenVariantExists() throws Exception {
    streamingService.setSession(buildAbrSession());

    var result =
        mockMvc
            .perform(
                get("/api/stream/{sessionId}/{variantLabel}/stream.m3u8", SESSION_ID, "720p")
                    .param("t", "unit-token"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-TARGETDURATION:");
  }

  @Test
  @DisplayName("Should return 404 for variant playlist when variant not found")
  void shouldReturn404ForVariantPlaylistWhenVariantNotFound() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(
            get("/api/stream/{sessionId}/{variantLabel}/stream.m3u8", SESSION_ID, "360p")
                .param("t", "unit-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve variant segment when variant and segment exist")
  void shouldServeVariantSegmentWhenVariantAndSegmentExist() throws Exception {
    streamingService.setSession(buildAbrSession());
    var segmentData = new byte[] {0x47, 0x00, 0x11, 0x10};
    segmentStore.addSegment(SESSION_ID, "720p/segment0.ts", segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.ts", SESSION_ID, "720p"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp2t");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should return 404 for variant segment when variant not found")
  void shouldReturn404ForVariantSegmentWhenVariantNotFound() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.ts", SESSION_ID, "360p"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve default variant segment when using original URL")
  void shouldServeDefaultVariantSegmentWhenUsingOriginalUrl() throws Exception {
    streamingService.setSession(buildMpegtsSession());
    var segmentData = new byte[] {0x47};
    segmentStore.addSegment(SESSION_ID, "segment0.ts", segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/segment0.ts", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should return 400 when segment name contains path traversal")
  void shouldReturn400WhenSegmentNameContainsPathTraversal() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{segmentName}", SESSION_ID, "..segment0.ts"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should serve variant init segment when variant uses fMP4")
  void shouldServeVariantInitSegmentWhenVariantUsesFmp4() throws Exception {
    var session = buildAbrFmp4Session();
    streamingService.setSession(session);
    var initData = new byte[] {0x00, 0x00, 0x00, 0x20};
    segmentStore.addSegment(SESSION_ID, "1080p/init.mp4", initData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/{variantLabel}/init.mp4", SESSION_ID, "1080p"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
    assertThat(result.getResponse().getContentLength()).isEqualTo(initData.length);
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(initData);
  }

  @Test
  @DisplayName("Should return 404 for variant init segment when variant not found")
  void shouldReturn404ForVariantInitSegmentWhenVariantNotFound() throws Exception {
    streamingService.setSession(buildAbrFmp4Session());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/init.mp4", SESSION_ID, "360p"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return 400 when variant label contains path traversal")
  void shouldReturn400WhenVariantLabelContainsPathTraversal() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.ts", SESSION_ID, "..720p"))
        .andExpect(status().isBadRequest());
  }

  private class BoundAuthorizationService implements AuthorizationService {

    @Override
    public AuthenticatedIdentity currentIdentity() {
      return AuthenticatedIdentity.builder()
          .accountId(UUID.randomUUID())
          .role(AccountRole.USER)
          .authSessionId(UUID.randomUUID())
          .scope(TokenScope.PLAYBACK)
          .householdId(UUID.randomUUID())
          .householdRole(HouseholdRole.MEMBER)
          .profileId(UUID.randomUUID())
          .streamSessionId(boundStreamSession.get())
          .build();
    }

    @Override
    public String currentTokenValue() {
      return VALIDATED_TOKEN;
    }

    @Override
    public Instant currentTokenExpiry() {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID requireAccountId() {
      return currentIdentity().accountId();
    }

    @Override
    public UUID requireHousehold() {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID requireProfile() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isServerAdmin() {
      return false;
    }

    @Override
    public void requireServerAdmin() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void requireHouseholdRole(com.streamarr.server.domain.auth.HouseholdRole minimum) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean canViewActivityOf(UUID profileId) {
      return false;
    }
  }

  private static class StubStreamingService implements StreamingService {

    private StreamSession session;

    void setSession(StreamSession session) {
      this.session = session;
    }

    @Override
    public StreamSession createSession(CreateStreamSessionCommand command) {
      return session;
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      if (session != null && session.getSessionId().equals(request.streamSessionId())) {
        return Optional.of(session);
      }
      return Optional.empty();
    }

    @Override
    public void destroySession(UUID sessionId) {
      // no-op for test fake
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return session != null ? List.of(session) : Collections.emptyList();
    }

    @Override
    public int getActiveSessionCount() {
      return session != null ? 1 : 0;
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      // no-op for test fake
    }

    @Override
    public boolean isTranscodeActive(UUID sessionId, String variantLabel) {
      return session != null && session.getSessionId().equals(sessionId);
    }
  }
}
