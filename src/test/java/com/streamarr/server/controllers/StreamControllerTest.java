package com.streamarr.server.controllers;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static com.streamarr.server.fixtures.StreamSessionFixture.withActiveVariantHandles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private FakeStreamingService streamingService;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private FakeTranscodeExecutor transcodeExecutor;
  private StreamingProperties properties;
  private HlsPlaylistService playlistService;
  private StreamController controller;

  @BeforeEach
  void setUp() {
    streamingService = new FakeStreamingService();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    transcodeExecutor = new FakeTranscodeExecutor();
    properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(8)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(Duration.ofMillis(200))
            .build();
    playlistService = new HlsPlaylistService(properties);
    boundStreamSession.set(SESSION_ID);
    controller =
        new StreamController(
            streamingService,
            playlistService,
            coordinatorOver(segmentStore),
            new FakeAuthorizationService(this::boundIdentity, VALIDATED_TOKEN));
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  private SegmentDeliveryCoordinator coordinatorOver(FakeSegmentStore store) {
    return StreamingRigFixture.streamingRigBuilder()
        .segmentStore(store)
        .transcodeExecutor(transcodeExecutor)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .pollInterval(Duration.ofMillis(10))
        .build()
        .coordinator();
  }

  @Test
  @DisplayName("Should return multivariant playlist with correct content type when session exists")
  void shouldReturnMultivariantPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    var result =
        mockMvc
            .perform(
                get("/api/stream/{sessionId}/multivariant.m3u8", SESSION_ID)
                    .param("t", "unit-token"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-STREAM-INF:");
  }

  @Test
  @DisplayName(
      "Should return 404 for the retired master playlist alias when handling a playback request")
  void shouldReturn404ForTheRetiredMasterPlaylistAliasWhenHandlingPlaybackRequest()
      throws Exception {
    streamingService.setSession(buildMpegtsSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/master.m3u8", SESSION_ID).param("t", "unit-token"))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"multivariant.m3u8", "stream.m3u8"})
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
  @ValueSource(strings = {"multivariant.m3u8", "stream.m3u8", "segment0.ts", "init.mp4"})
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
  @ValueSource(
      strings = {
        "multivariant.m3u8",
        "stream.m3u8",
        "segment0.ts",
        "init.mp4",
        "720p/stream.m3u8",
        "720p/init.mp4",
        "720p/segment0.ts"
      })
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
  @DisplayName("Should return 404 when the runtime session has ended and the segment is missing")
  void shouldReturn404WhenTheRuntimeSessionHasEndedAndTheSegmentIsMissing() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/segment0.ts", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "Should return 404 without disturbing the producer when the segment name matches no naming"
          + " scheme")
  void shouldReturn404WithoutDisturbingTheProducerWhenTheSegmentNameMatchesNoNamingScheme()
      throws Exception {
    var session = buildMpegtsSession();
    streamingService.setSession(session);
    var handle =
        transcodeExecutor.start(
            TranscodeRequest.builder()
                .sessionId(SESSION_ID)
                .sourcePath(session.getSourcePath())
                .transcodeDecision(session.getTranscodeDecision())
                .build());
    session.setHandle(handle);
    runtimeRegistry.save(session);

    mockMvc
        .perform(get("/api/stream/{sessionId}/foo.ts", SESSION_ID))
        .andExpect(status().isNotFound());

    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
    assertThat(transcodeExecutor.isRunning(SESSION_ID, StreamSession.defaultVariant())).isTrue();
  }

  @Test
  @DisplayName("Should return 503 when delivery is cancelled by server shutdown")
  void shouldReturn503WhenDeliveryIsCancelledByServerShutdown() throws Exception {
    var session = buildMpegtsSession();
    streamingService.setSession(session);
    session.setHandle(mintHandle(1L, TranscodeStatus.ACTIVE));
    runtimeRegistry.save(session);

    var response = new AtomicReference<ResponseEntity<byte[]>>();
    var worker =
        new Thread(
            () -> {
              // A pre-set interrupt makes the first delivery wait observe the shutdown signal.
              Thread.currentThread().interrupt();
              response.set(controller.getSegment(SESSION_ID, "segment1.ts"));
            });
    worker.start();
    worker.join(Duration.ofSeconds(2).toMillis());

    assertThat(response.get()).isNotNull();
    assertThat(response.get().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  @DisplayName("Should return 404 when the segment index does not fit the naming scheme")
  void shouldReturn404WhenTheSegmentIndexDoesNotFitTheNamingScheme() throws Exception {
    var session = buildMpegtsSession();
    streamingService.setSession(session);
    session.setHandle(mintHandle(1L, TranscodeStatus.ACTIVE));
    runtimeRegistry.save(session);

    mockMvc
        .perform(get("/api/stream/{sessionId}/segment99999999999999999999.ts", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return 404 when segment vanishes between the existence check and the read")
  void shouldReturn404WhenSegmentVanishesBetweenTheExistenceCheckAndTheRead() throws Exception {
    streamingService.setSession(buildMpegtsSession());
    var throwingStore =
        new FakeSegmentStore() {
          @Override
          public byte[] readSegment(UUID sessionId, String segmentName) {
            throw new TranscodeException("Segment not found: " + segmentName);
          }
        };
    throwingStore.addSegment(SESSION_ID, "segment0.ts", new byte[] {0x47});
    var raceController =
        new StreamController(
            streamingService,
            playlistService,
            coordinatorOver(throwingStore),
            new FakeAuthorizationService(this::boundIdentity, VALIDATED_TOKEN));
    var raceMockMvc = MockMvcBuilders.standaloneSetup(raceController).build();

    raceMockMvc
        .perform(get("/api/stream/{sessionId}/segment0.ts", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return 503 with no body when recovery is exhausted")
  void shouldReturn503WithNoBodyWhenRecoveryExhausted() throws Exception {
    var session = buildMpegtsSession();
    streamingService.setSession(session);
    session.setHandle(mintHandle(1L, TranscodeStatus.FAILED));
    runtimeRegistry.save(session);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/segment0.ts", SESSION_ID))
            .andExpect(status().isServiceUnavailable())
            .andReturn();

    assertThat(result.getResponse().getContentLength()).isZero();
    assertThat(result.getResponse().getHeader("Retry-After")).isNull();
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

  private AuthenticatedIdentity boundIdentity() {
    return defaultIdentityBuilder().streamSessionId(boundStreamSession.get()).build();
  }
}
