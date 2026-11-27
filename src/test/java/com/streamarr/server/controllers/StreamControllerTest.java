package com.streamarr.server.controllers;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static com.streamarr.server.fixtures.StreamSessionFixture.withActiveVariantHandles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.properties.RemoteTranscodeProperties;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.streaming.DirectPlayMetrics;
import com.streamarr.server.services.streaming.DirectPlaySourceService;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.FileBackedStoredSegment;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StoredSegment;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.Builder;
import org.apache.catalina.Globals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("UnitTest")
@DisplayName("Stream Controller Tests")
class StreamControllerTest {

  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final String VALIDATED_TOKEN = "validated-context-token";
  private static final String SPOOFED_PARAM = "spoofed<script>-param";
  // Bytes outside ASCII cannot match a multipart answer's part headers.
  private static final byte[] SEGMENT_BYTES = {(byte) 0xA1, (byte) 0xB2, (byte) 0xC3, (byte) 0xD4};

  private MockMvc mockMvc;
  private final AtomicReference<UUID> boundStreamSession = new AtomicReference<>(SESSION_ID);
  private FakeStreamingService streamingService;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private FakeTranscodeExecutor transcodeExecutor;
  private StreamingProperties properties;
  private HlsPlaylistService playlistService;
  private StreamController controller;
  private SimpleMeterRegistry meterRegistry;
  private DirectPlayMetrics directPlayMetrics;

  @TempDir private Path mediaRoot;

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
    meterRegistry = new SimpleMeterRegistry();
    directPlayMetrics = new DirectPlayMetrics(meterRegistry);
    controller =
        new StreamController(
            streamingService,
            playlistService,
            coordinatorOver(segmentStore),
            new FakeAuthorizationService(this::boundIdentity, VALIDATED_TOKEN),
            directPlaySources(),
            directPlayMetrics);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  private DirectPlaySourceService directPlaySources() {
    return new DirectPlaySourceService(
        streamingService,
        new RemoteTranscodeProperties(UUID.randomUUID(), mediaRoot.toString()),
        directPlayMetrics);
  }

  private SegmentDeliveryCoordinator coordinatorOver(SegmentStore store) {
    return StreamingRigFixture.streamingRigBuilder()
        .segmentStore(store)
        .transcodeExecutor(transcodeExecutor)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .heldRequests(transcodeExecutor.heldSegmentRequests())
        .build()
        .coordinator();
  }

  @Test
  @DisplayName("Should return multivariant playlist with correct content type when session exists")
  void shouldReturnMultivariantPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    streamingService.setSession(buildSession());

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
    streamingService.setSession(buildSession());

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
    streamingService.setSession(buildSession());

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
  @ValueSource(strings = {"multivariant.m3u8", "stream.m3u8", "segment0.m4s", "init.mp4"})
  @DisplayName("Should reject stream request when token is bound to another stream session")
  void shouldRejectStreamRequestWhenTokenIsBoundToAnotherStreamSession(String path) {
    streamingService.setSession(buildSession());
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
        "segment0.m4s",
        "init.mp4",
        "720p/stream.m3u8",
        "720p/init.mp4",
        "720p/segment0.m4s"
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
    streamingService.setSession(buildSession());

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

  @ParameterizedTest
  @ValueSource(strings = {"segment0.ts", "720p/segment0.ts"})
  @DisplayName("Should return 404 when a segment is requested as MPEG-TS")
  void shouldReturn404WhenSegmentIsRequestedAsMpegTs(String segmentName) throws Exception {
    streamingService.setSession(buildAbrSession());
    segmentStore.addSegment(SESSION_ID, segmentName, new byte[] {0x47, 0x00, 0x11, 0x10});

    mockMvc
        .perform(get("/api/stream/{sessionId}/" + segmentName, SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve m4s segment with correct content type when segment is available")
  void shouldServeM4sSegmentWithCorrectContentTypeWhenSegmentIsAvailable() throws Exception {
    streamingService.setSession(buildSession());
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("segmentAnswers")
  @DisplayName("Should close the opened segment when its answer ends")
  void shouldCloseOpenedSegmentWhenItsAnswerEnds(SegmentAnswer expected) throws Exception {
    streamingService.setSession(buildSession());
    segmentStore.addSegment(SESSION_ID, "segment0.m4s", SEGMENT_BYTES);
    var request = get("/api/stream/{sessionId}/segment0.m4s", SESSION_ID);
    if (expected.range() != null) {
      request.header(HttpHeaders.RANGE, expected.range());
    }

    var answer = mockMvc.perform(request).andReturn().getResponse();

    assertThat(answer.getStatus()).isEqualTo(expected.status());
    assertThat(expected.servedRanges())
        .allSatisfy(served -> assertThat(answer.getContentAsByteArray()).containsSequence(served));
    assertThat(segmentStore.openSegmentCount()).isZero();
  }

  private static Stream<SegmentAnswer> segmentAnswers() {
    return Stream.of(
        SegmentAnswer.builder().status(200).servedRanges(List.of(SEGMENT_BYTES)).build(),
        SegmentAnswer.builder()
            .range("bytes=1-2")
            .status(206)
            .servedRanges(List.of(new byte[] {(byte) 0xB2, (byte) 0xC3}))
            .build(),
        SegmentAnswer.builder()
            .range("bytes=0-0,2-3")
            .status(206)
            .servedRanges(List.of(new byte[] {(byte) 0xA1}, new byte[] {(byte) 0xC3, (byte) 0xD4}))
            .build(),
        SegmentAnswer.builder().range("bytes=9-").status(416).servedRanges(List.of()).build());
  }

  // The bytes of each range that the answer's body must hold.
  @Builder
  private record SegmentAnswer(String range, int status, List<byte[]> servedRanges) {

    @Override
    public String toString() {
      return "Range " + range + " answers " + status;
    }
  }

  @Test
  @DisplayName("Should return 404 when the runtime session has ended and the segment is missing")
  void shouldReturn404WhenTheRuntimeSessionHasEndedAndTheSegmentIsMissing() throws Exception {
    streamingService.setSession(buildSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/segment0.m4s", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "Should return 404 without disturbing the producer when the segment name matches no naming"
          + " scheme")
  void shouldReturn404WithoutDisturbingTheProducerWhenTheSegmentNameMatchesNoNamingScheme()
      throws Exception {
    var session = buildSession();
    streamingService.setSession(session);
    var handle =
        transcodeExecutor.start(
            TranscodeRequest.builder()
                .sessionId(SESSION_ID)
                .sourcePath(session.getSourcePath())
                .transcodeDecision(session.getTranscodeDecision())
                .encoderPreset(EncoderPreset.FASTER)
                .build());
    session.setHandle(handle);
    runtimeRegistry.save(session);

    mockMvc
        .perform(get("/api/stream/{sessionId}/foo.m4s", SESSION_ID))
        .andExpect(status().isNotFound());

    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
    assertThat(transcodeExecutor.isRunning(SESSION_ID, StreamSession.defaultVariant())).isTrue();
  }

  @Test
  @DisplayName("Should return 503 when delivery is cancelled by server shutdown")
  void shouldReturn503WhenDeliveryIsCancelledByServerShutdown() throws Exception {
    var session = buildSession();
    streamingService.setSession(session);
    session.setHandle(mintHandle(1L, TranscodeStatus.ACTIVE));
    runtimeRegistry.save(session);

    var response = new AtomicReference<ResponseEntity<Resource>>();
    var worker =
        new Thread(
            () -> {
              // A pre-set interrupt makes the first delivery wait observe the shutdown signal.
              Thread.currentThread().interrupt();
              response.set(controller.getSegment(SESSION_ID, "segment1.m4s"));
            });
    worker.start();
    worker.join(Duration.ofSeconds(2).toMillis());

    assertThat(response.get()).isNotNull();
    assertThat(response.get().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  @DisplayName("Should return 404 when the segment index does not fit the naming scheme")
  void shouldReturn404WhenTheSegmentIndexDoesNotFitTheNamingScheme() throws Exception {
    var session = buildSession();
    streamingService.setSession(session);
    session.setHandle(mintHandle(1L, TranscodeStatus.ACTIVE));
    runtimeRegistry.save(session);

    mockMvc
        .perform(get("/api/stream/{sessionId}/segment99999999999999999999.m4s", SESSION_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return 503 with no body when recovery is exhausted")
  void shouldReturn503WithNoBodyWhenRecoveryExhausted() throws Exception {
    var session = buildSession();
    streamingService.setSession(session);
    session.setHandle(mintHandle(1L, TranscodeStatus.FAILED));
    runtimeRegistry.save(session);
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/segment0.m4s", SESSION_ID))
            .andExpect(status().isServiceUnavailable())
            .andReturn();

    assertThat(result.getResponse().getContentLength()).isZero();
    assertThat(result.getResponse().getHeader("Retry-After")).isNull();
  }

  @Test
  @DisplayName("Should serve the initialization segment when the session exists")
  void shouldServeInitializationSegmentWhenSessionExists() throws Exception {
    streamingService.setSession(buildSession());
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

  private StreamSession buildSession() {
    return defaultSessionBuilder().sessionId(SESSION_ID).build();
  }

  // --- Variant routing tests ---

  private StreamSession buildAbrSession() {
    var session =
        defaultSessionBuilder()
            .sessionId(SESSION_ID)
            .mediaProbe(defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000).build())
            .transcodeDecision(fullTranscodeDecision("h264"))
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
    segmentStore.addSegment(SESSION_ID, "720p/segment0.m4s", segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.m4s", SESSION_ID, "720p"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should return 404 for variant segment when variant not found")
  void shouldReturn404ForVariantSegmentWhenVariantNotFound() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.m4s", SESSION_ID, "360p"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve default variant segment when using original URL")
  void shouldServeDefaultVariantSegmentWhenUsingOriginalUrl() throws Exception {
    streamingService.setSession(buildSession());
    var segmentData = new byte[] {0x47};
    segmentStore.addSegment(SESSION_ID, "segment0.m4s", segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/segment0.m4s", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName(
      "Should serve a fixed quality's segments at the URLs that its playlists advertise when the"
          + " worker uploaded them")
  void shouldServeFixedQualitysSegmentsAtUrlsThatItsPlaylistsAdvertiseWhenWorkerUploadedThem()
      throws Exception {
    streamingService.setSession(buildFixedQualitySession());
    var initData = new byte[] {0x00, 0x00, 0x00, 0x20};
    var segmentData = new byte[] {0x47, 0x00};
    // The names under which the worker's uploads store the default variant's segments.
    segmentStore.addSegment(SESSION_ID, "init.mp4", initData);
    segmentStore.addSegment(SESSION_ID, "segment0.m4s", segmentData);
    var multivariantUri = URI.create("/api/stream/" + SESSION_ID + "/multivariant.m3u8");

    var mediaPlaylistUri = multivariantUri.resolve(firstUriLine(fetchText(multivariantUri)));
    var mediaPlaylist = fetchText(mediaPlaylistUri);
    var initUri = mediaPlaylistUri.resolve(mapUri(mediaPlaylist));
    var segmentUri = mediaPlaylistUri.resolve(firstUriLine(mediaPlaylist));

    assertThat(fetchBytes(initUri)).isEqualTo(initData);
    assertThat(fetchBytes(segmentUri)).isEqualTo(segmentData);
  }

  // A fixed quality encodes one variant, the default variant, as its whole ladder.
  private StreamSession buildFixedQualitySession() {
    var session =
        defaultSessionBuilder()
            .sessionId(SESSION_ID)
            .mediaProbe(defaultProbeBuilder().videoCodec("hevc").bitrate(8_000_000).build())
            .transcodeDecision(fullTranscodeDecision("h264"))
            .variants(
                List.of(
                    defaultVariantBuilder()
                        .width(1920)
                        .height(1080)
                        .videoBitrate(8_000_000L)
                        .label(StreamSession.defaultVariant())
                        .build()))
            .build();
    return withActiveVariantHandles(session);
  }

  private String fetchText(URI uri) throws Exception {
    return mockMvc
        .perform(get(uri))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private byte[] fetchBytes(URI uri) throws Exception {
    return mockMvc
        .perform(get(uri))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  private static String firstUriLine(String playlist) {
    return playlist.lines().filter(line -> !line.startsWith("#")).findFirst().orElseThrow();
  }

  private static String mapUri(String playlist) {
    var prefix = "#EXT-X-MAP:URI=\"";
    var line = playlist.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    return line.substring(prefix.length(), line.length() - 1);
  }

  @Test
  @DisplayName("Should return 400 when segment name contains path traversal")
  void shouldReturn400WhenSegmentNameContainsPathTraversal() throws Exception {
    streamingService.setSession(buildSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{segmentName}", SESSION_ID, "..segment0.m4s"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should serve the variant initialization segment when the variant exists")
  void shouldServeVariantInitializationSegmentWhenVariantExists() throws Exception {
    var session = buildAbrSession();
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
  @DisplayName(
      "Should return 404 for the variant initialization segment when the variant is not found")
  void shouldReturn404ForVariantInitializationSegmentWhenVariantNotFound() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/init.mp4", SESSION_ID, "360p"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return 400 when variant label contains path traversal")
  void shouldReturn400WhenVariantLabelContainsPathTraversal() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.m4s", SESSION_ID, "..720p"))
        .andExpect(status().isBadRequest());
  }

  @ParameterizedTest(name = "{0} {1} with range [{2}] answers {3}")
  @CsvSource({
    "GET, source.mp4, , 200",
    "GET, source.mp4, bytes=0-1, 206",
    "GET, source.mp4, bytes=3-, 416",
    "GET, source.mov, , 404",
    "HEAD, source.mp4, , 200",
    "HEAD, source.mov, , 404"
  })
  @DisplayName("Should count each direct-play answer by its status when a request is answered")
  void shouldCountEachDirectPlayAnswerByItsStatusWhenRequestIsAnswered(
      String method, String resource, String range, int expectedStatus) throws Exception {
    startDirectPlay();
    var request =
        request(
                HttpMethod.valueOf(method),
                "/api/stream/{sessionId}/{resource}",
                SESSION_ID,
                resource)
            .param("t", "unit-token");
    if (range != null) {
      request.header(HttpHeaders.RANGE, range);
    }

    mockMvc.perform(request).andExpect(status().is(expectedStatus));

    assertThat(
            meterRegistry
                .get("streamarr.streaming.direct_play_requests")
                .tag("status", String.valueOf(expectedStatus))
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should count the served bytes and close the source when the answer ends")
  void shouldCountTheServedBytesAndCloseTheSourceWhenTheAnswerEnds() throws Exception {
    startDirectPlay();

    mockMvc
        .perform(get("/api/stream/{sessionId}/source.mp4", SESSION_ID).param("t", "unit-token"))
        .andExpect(status().isOk());

    assertThat(meterRegistry.get("streamarr.streaming.direct_play_bytes_served").counter().count())
        .isEqualTo(3);
    assertThat(meterRegistry.get("streamarr.streaming.direct_play_open_responses").gauge().value())
        .isZero();
  }

  private void startDirectPlay() throws IOException {
    var source = Files.write(mediaRoot.resolve("movie.mp4"), new byte[] {1, 2, 3});
    streamingService.addSession(
        directPlaySessionBuilder().sessionId(SESSION_ID).sourcePath(source).build());
  }

  @Test
  @DisplayName("Should hand the whole segment file to Tomcat when the connection supports sendfile")
  void shouldHandWholeSegmentFileToTomcatWhenConnectionSupportsSendfile(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(segmentRequest().requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("5");
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR))
        .isEqualTo(segmentFolder.resolve(SESSION_ID.toString()).resolve("segment0.m4s").toString());
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILE_START_ATTR)).isEqualTo(0L);
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILE_END_ATTR)).isEqualTo(5L);
  }

  @Test
  @DisplayName("Should hand one byte range of the segment file to Tomcat when a range is requested")
  void shouldHandOneByteRangeOfSegmentFileToTomcatWhenRangeIsRequested(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(
                segmentRequest()
                    .header(HttpHeaders.RANGE, "bytes=1-3")
                    .requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isPartialContent())
            .andReturn();

    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 1-3/5");
    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("3");
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILE_START_ATTR)).isEqualTo(1L);
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILE_END_ATTR)).isEqualTo(4L);
  }

  @Test
  @DisplayName("Should write the segment itself when the connection does not support sendfile")
  void shouldWriteSegmentItselfWhenConnectionDoesNotSupportSendfile(@TempDir Path segmentFolder)
      throws Exception {
    var segmentData = new byte[] {1, 2, 3, 4, 5};
    var store = storeSegmentLocally(segmentFolder, segmentData);

    var result = overStore(store).perform(segmentRequest()).andExpect(status().isOk()).andReturn();

    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNull();
  }

  @Test
  @DisplayName("Should write the ranges itself when several byte ranges are requested")
  void shouldWriteRangesItselfWhenSeveralByteRangesAreRequested(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(
                segmentRequest()
                    .header(HttpHeaders.RANGE, "bytes=0-0,2-2")
                    .requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isPartialContent())
            .andReturn();

    assertThat(result.getResponse().getContentType()).startsWith("multipart/byteranges");
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNull();
  }

  @Test
  @DisplayName("Should send no segment file when the segment is requested with HEAD")
  void shouldSendNoSegmentFileWhenSegmentIsRequestedWithHead(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(
                request(HttpMethod.HEAD, "/api/stream/{sessionId}/segment0.m4s", SESSION_ID)
                    .requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNull();
  }

  @Test
  @DisplayName("Should write the empty segment itself when the stored segment has no bytes")
  void shouldWriteEmptySegmentItselfWhenStoredSegmentHasNoBytes(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[0]);

    var result =
        overStore(store)
            .perform(segmentRequest().requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNull();
  }

  @Test
  @DisplayName("Should answer 416 without a body when the requested range starts past the segment")
  void shouldAnswer416WithoutBodyWhenRequestedRangeStartsPastSegment(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(
                segmentRequest()
                    .header(HttpHeaders.RANGE, "bytes=9-")
                    .requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isRequestedRangeNotSatisfiable())
            .andReturn();

    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */5");
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNull();
  }

  @Test
  @DisplayName("Should commit the answer's headers when the segment is handed to Tomcat")
  void shouldCommitAnswersHeadersWhenSegmentIsHandedToTomcat(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(segmentRequest().requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNotNull();
    assertThat(result.getResponse().isCommitted()).isTrue();
  }

  @Test
  @DisplayName("Should close the opened segment when the segment is handed to Tomcat")
  void shouldCloseOpenedSegmentWhenSegmentIsHandedToTomcat(@TempDir Path segmentFolder)
      throws Exception {
    var store =
        new OpenCountingStore(storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5}));

    var result =
        overStore(store)
            .perform(segmentRequest().requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILENAME_ATTR)).isNotNull();
    assertThat(store.openSegmentCount()).isZero();
  }

  // RFC 9110 section 14.1.2: a range without a last position runs to the representation's end.
  @Test
  @DisplayName("Should hand the rest of the segment to Tomcat when a range has no last position")
  void shouldHandRestOfSegmentToTomcatWhenRangeHasNoLastPosition(@TempDir Path segmentFolder)
      throws Exception {
    var store = storeSegmentLocally(segmentFolder, new byte[] {1, 2, 3, 4, 5});

    var result =
        overStore(store)
            .perform(
                segmentRequest()
                    .header(HttpHeaders.RANGE, "bytes=3-")
                    .requestAttr(Globals.SENDFILE_SUPPORTED_ATTR, true))
            .andExpect(status().isPartialContent())
            .andReturn();

    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 3-4/5");
    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("2");
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILE_START_ATTR)).isEqualTo(3L);
    assertThat(result.getRequest().getAttribute(Globals.SENDFILE_FILE_END_ATTR)).isEqualTo(5L);
  }

  private static MockHttpServletRequestBuilder segmentRequest() {
    return get("/api/stream/{sessionId}/segment0.m4s", SESSION_ID);
  }

  private LocalSegmentStore storeSegmentLocally(Path segmentFolder, byte[] segmentData) {
    streamingService.setSession(buildSession());
    var store = new LocalSegmentStore(segmentFolder);
    store.storeSegment(SESSION_ID, "segment0.m4s", segmentData);
    return store;
  }

  private MockMvc overStore(SegmentStore store) {
    return MockMvcBuilders.standaloneSetup(
            new StreamController(
                streamingService,
                playlistService,
                coordinatorOver(store),
                new FakeAuthorizationService(this::boundIdentity, VALIDATED_TOKEN),
                directPlaySources(),
                directPlayMetrics))
        .build();
  }

  private AuthenticatedIdentity boundIdentity() {
    return defaultIdentityBuilder().streamSessionId(boundStreamSession.get()).build();
  }

  /** Opens segments through a local segment store and counts the ones not closed yet. */
  private static final class OpenCountingStore implements SegmentStore {

    private final LocalSegmentStore store;
    private final Set<StoredSegment> openSegments = ConcurrentHashMap.newKeySet();

    private OpenCountingStore(LocalSegmentStore store) {
      this.store = store;
    }

    private int openSegmentCount() {
      return openSegments.size();
    }

    @Override
    public Optional<StoredSegment> openSegment(UUID sessionId, String segmentName) {
      return store.openSegment(sessionId, segmentName).map(this::counted);
    }

    // A local segment store opens file-backed segments; the counted one stays file-backed, so
    // the controller still hands it to Tomcat.
    private StoredSegment counted(StoredSegment opened) {
      var file = (FileBackedStoredSegment) opened;
      var counted =
          new FileBackedStoredSegment() {
            @Override
            public Path path() {
              return file.path();
            }

            @Override
            public long length() {
              return file.length();
            }

            @Override
            public InputStream newInputStream() {
              return file.newInputStream();
            }

            @Override
            public void close() {
              file.close();
              openSegments.remove(this);
            }
          };
      openSegments.add(counted);
      return counted;
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      return store.segmentExists(sessionId, segmentName);
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
      return store.prepareSegment(sessionId, segmentName);
    }

    @Override
    public void deleteSession(UUID sessionId) {
      store.deleteSession(sessionId);
    }
  }
}
