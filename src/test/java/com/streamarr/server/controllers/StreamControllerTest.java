package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.StreamingService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("UnitTest")
@DisplayName("Stream Controller Tests")
class StreamControllerTest {

  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final MediaType HLS_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.apple.mpegurl");

  private MockMvc mockMvc;
  private StubStreamingService streamingService;
  private FakeSegmentStore segmentStore;
  private HlsPlaylistService playlistService;

  @BeforeEach
  void setUp() {
    streamingService = new StubStreamingService();
    segmentStore = new FakeSegmentStore();
    playlistService =
        new HlsPlaylistService(
            StreamingProperties.builder()
                .maxConcurrentTranscodes(8)
                .segmentDurationSeconds(6)
                .sessionTimeoutSeconds(60)
                .build());
    var controller = new StreamController(streamingService, playlistService, segmentStore);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  @DisplayName("Should return master playlist with correct content type when session exists")
  void shouldReturnMasterPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/master.m3u8", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-STREAM-INF:");
  }

  @Test
  @DisplayName("Should return 404 for master playlist when session not found")
  void shouldReturn404ForMasterPlaylistWhenSessionNotFound() throws Exception {
    mockMvc
        .perform(get("/api/stream/{sessionId}/master.m3u8", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should return media playlist with correct content type when session exists")
  void shouldReturnMediaPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    streamingService.setSession(buildMpegtsSession());

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/stream.m3u8", SESSION_ID))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-TARGETDURATION:");
    assertThat(result.getResponse().getContentAsString()).contains("#EXT-X-ENDLIST");
  }

  @Test
  @DisplayName("Should return 404 for media playlist when session not found")
  void shouldReturn404ForMediaPlaylistWhenSessionNotFound() throws Exception {
    mockMvc
        .perform(get("/api/stream/{sessionId}/stream.m3u8", UUID.randomUUID()))
        .andExpect(status().isNotFound());
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
  @DisplayName("Should return 404 for segment when session not found")
  void shouldReturn404ForSegmentWhenSessionNotFound() throws Exception {
    mockMvc
        .perform(get("/api/stream/{sessionId}/segment0.ts", UUID.randomUUID()))
        .andExpect(status().isNotFound());
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

  @Test
  @DisplayName("Should return 404 for init segment when session not found")
  void shouldReturn404ForInitSegmentWhenSessionNotFound() throws Exception {
    mockMvc
        .perform(get("/api/stream/{sessionId}/init.mp4", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  private StreamSession buildMpegtsSession() {
    return StreamSession.builder()
        .sessionId(SESSION_ID)
        .mediaFileId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .mediaProbe(
            MediaProbe.builder()
                .duration(Duration.ofMinutes(120))
                .framerate(24.0)
                .width(1920)
                .height(1080)
                .videoCodec("h264")
                .audioCodec("aac")
                .bitrate(5_000_000)
                .build())
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily("h264")
                .audioCodec("aac")
                .containerFormat(ContainerFormat.MPEGTS)
                .needsKeyframeAlignment(true)
                .build())
        .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
        .createdAt(Instant.now())
        .lastAccessedAt(Instant.now())
        .build();
  }

  private StreamSession buildFmp4Session() {
    return StreamSession.builder()
        .sessionId(SESSION_ID)
        .mediaFileId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .mediaProbe(
            MediaProbe.builder()
                .duration(Duration.ofMinutes(120))
                .framerate(24.0)
                .width(1920)
                .height(1080)
                .videoCodec("hevc")
                .audioCodec("aac")
                .bitrate(5_000_000)
                .build())
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily("av1")
                .audioCodec("aac")
                .containerFormat(ContainerFormat.FMP4)
                .needsKeyframeAlignment(false)
                .build())
        .options(StreamingOptions.builder().supportedCodecs(List.of("av1")).build())
        .createdAt(Instant.now())
        .lastAccessedAt(Instant.now())
        .build();
  }

  // --- Variant routing tests ---

  private StreamSession buildAbrSession() {
    var variants =
        List.of(
            QualityVariant.builder()
                .width(1920)
                .height(1080)
                .videoBitrate(5_000_000L)
                .audioBitrate(128_000L)
                .label("1080p")
                .build(),
            QualityVariant.builder()
                .width(1280)
                .height(720)
                .videoBitrate(3_000_000L)
                .audioBitrate(128_000L)
                .label("720p")
                .build());

    var session =
        StreamSession.builder()
            .sessionId(SESSION_ID)
            .mediaFileId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .duration(Duration.ofMinutes(120))
                    .framerate(24.0)
                    .width(1920)
                    .height(1080)
                    .videoCodec("hevc")
                    .audioCodec("aac")
                    .bitrate(8_000_000)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioCodec("aac")
                    .containerFormat(ContainerFormat.MPEGTS)
                    .needsKeyframeAlignment(false)
                    .build())
            .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
            .variants(variants)
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .build();

    for (var variant : variants) {
      session.setVariantHandle(variant.label(), new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    }
    return session;
  }

  @Test
  @DisplayName("Should serve variant media playlist when variant exists")
  void shouldServeVariantMediaPlaylistWhenVariantExists() throws Exception {
    streamingService.setSession(buildAbrSession());

    var result =
        mockMvc
            .perform(get("/api/stream/{sessionId}/{variantLabel}/stream.m3u8", SESSION_ID, "720p"))
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
        .perform(get("/api/stream/{sessionId}/{variantLabel}/stream.m3u8", SESSION_ID, "360p"))
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
  @DisplayName("Should return 400 when variant label contains path traversal")
  void shouldReturn400WhenVariantLabelContainsPathTraversal() throws Exception {
    streamingService.setSession(buildAbrSession());

    mockMvc
        .perform(get("/api/stream/{sessionId}/{variantLabel}/segment0.ts", SESSION_ID, "..720p"))
        .andExpect(status().isBadRequest());
  }

  private static class StubStreamingService implements StreamingService {

    private StreamSession session;

    void setSession(StreamSession session) {
      this.session = session;
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
      return session;
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      if (session != null && session.getSessionId().equals(sessionId)) {
        return Optional.of(session);
      }
      return Optional.empty();
    }

    @Override
    public StreamSession seekSession(UUID sessionId, int positionSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroySession(UUID sessionId) {}

    @Override
    public Collection<StreamSession> getAllSessions() {
      return session != null ? List.of(session) : Collections.emptyList();
    }

    @Override
    public int getActiveSessionCount() {
      return session != null ? 1 : 0;
    }
  }
}
