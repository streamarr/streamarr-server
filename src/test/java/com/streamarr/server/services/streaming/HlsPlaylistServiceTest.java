package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("HLS Playlist Service Tests")
class HlsPlaylistServiceTest {

  private HlsPlaylistService service;
  private StreamingProperties properties;

  @BeforeEach
  void setUp() {
    properties = new StreamingProperties(8, 6, 60, null);
    service = new HlsPlaylistService(properties);
  }

  private StreamSession createSession(
      ContainerFormat container, TranscodeMode mode, int durationSeconds) {
    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .sourcePath(Path.of("/media/test.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .duration(Duration.ofSeconds(durationSeconds))
                    .framerate(23.976)
                    .width(1920)
                    .height(1080)
                    .videoCodec("h264")
                    .audioCodec("aac")
                    .bitrate(5_000_000L)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(mode)
                    .videoCodecFamily(container == ContainerFormat.FMP4 ? "av1" : "h264")
                    .audioCodec("aac")
                    .containerFormat(container)
                    .needsKeyframeAlignment(mode != TranscodeMode.FULL_TRANSCODE)
                    .build())
            .options(StreamingOptions.builder().supportedCodecs(List.of("h264", "av1")).build())
            .seekPosition(0)
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .activeRequestCount(new AtomicInteger(0))
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  @Test
  @DisplayName("Should start with EXTM3U when generating master playlist")
  void shouldStartWithExtm3uWhenGeneratingMasterPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).startsWith("#EXTM3U\n");
  }

  @Test
  @DisplayName(
      "Should include stream inf with bandwidth and resolution when generating master playlist")
  void shouldIncludeStreamInfWithBandwidthAndResolutionWhenGeneratingMasterPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("#EXT-X-STREAM-INF:");
    assertThat(playlist).contains("BANDWIDTH=");
    assertThat(playlist).contains("RESOLUTION=1920x1080");
  }

  @Test
  @DisplayName("Should point to stream playlist URL when generating master playlist")
  void shouldPointToStreamPlaylistUrlWhenGeneratingMasterPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("stream.m3u8");
  }

  @Test
  @DisplayName("Should use version 3 when container is MPEGTS")
  void shouldUseVersion3WhenContainerIsMpegts() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-VERSION:3");
  }

  @Test
  @DisplayName("Should use version 6 when container is fMP4")
  void shouldUseVersion6WhenContainerIsFmp4() {
    var session = createSession(ContainerFormat.FMP4, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-VERSION:6");
  }

  @Test
  @DisplayName("Should include EXT-X-MAP when container is fMP4")
  void shouldIncludeExtXMapWhenContainerIsFmp4() {
    var session = createSession(ContainerFormat.FMP4, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-MAP:URI=\"init.mp4\"");
  }

  @Test
  @DisplayName("Should not include EXT-X-MAP when container is MPEGTS")
  void shouldNotIncludeExtXMapWhenContainerIsMpegts() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).doesNotContain("#EXT-X-MAP");
  }

  @Test
  @DisplayName("Should use .ts extension when container is MPEGTS")
  void shouldUseTsExtensionWhenContainerIsMpegts() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 18);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("segment0.ts");
    assertThat(playlist).contains("segment1.ts");
    assertThat(playlist).contains("segment2.ts");
  }

  @Test
  @DisplayName("Should use .m4s extension when container is fMP4")
  void shouldUseM4sExtensionWhenContainerIsFmp4() {
    var session = createSession(ContainerFormat.FMP4, TranscodeMode.FULL_TRANSCODE, 18);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("segment0.m4s");
  }

  @Test
  @DisplayName("Should include end list when generating media playlist")
  void shouldIncludeEndListWhenGeneratingMediaPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-ENDLIST");
  }

  @Test
  @DisplayName("Should include playlist type VOD when generating media playlist")
  void shouldIncludePlaylistTypeVodWhenGeneratingMediaPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-PLAYLIST-TYPE:VOD");
  }

  @Test
  @DisplayName("Should include target duration when generating media playlist")
  void shouldIncludeTargetDurationWhenGeneratingMediaPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-TARGETDURATION:6");
  }

  @Test
  @DisplayName("Should calculate correct segment count when duration is 18 seconds")
  void shouldCalculateCorrectSegmentCountWhenDurationIs18Seconds() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 18);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("segment0.ts");
    assertThat(playlist).contains("segment1.ts");
    assertThat(playlist).contains("segment2.ts");
    assertThat(playlist).doesNotContain("segment3.ts");
  }

  @Test
  @DisplayName("Should start media playlist with EXTM3U when generating playlist")
  void shouldStartMediaPlaylistWithExtm3uWhenGeneratingPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).startsWith("#EXTM3U\n");
  }

  @Test
  @DisplayName("Should include codecs attribute when generating master playlist")
  void shouldIncludeCodecsAttributeWhenGeneratingMasterPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("CODECS=");
  }

  @Test
  @DisplayName("Should not contain master playlist tags when generating media playlist")
  void shouldNotContainMasterPlaylistTagsWhenGeneratingMediaPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).doesNotContain("#EXT-X-STREAM-INF");
  }

  @Test
  @DisplayName("Should set last segment duration when shorter than target duration")
  void shouldSetLastSegmentDurationWhenShorterThanTargetDuration() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 16);

    var playlist = service.generateMediaPlaylist(session);

    var lines = playlist.lines().toList();
    var extinfLines = lines.stream().filter(l -> l.startsWith("#EXTINF:")).toList();

    assertThat(extinfLines).hasSize(3);
    var lastExtinf = extinfLines.getLast();
    var duration = Double.parseDouble(lastExtinf.replace("#EXTINF:", "").replace(",", ""));
    assertThat(duration).isLessThanOrEqualTo(6.0);
  }

  private StreamSession createSessionWithDuration(
      ContainerFormat container, TranscodeMode mode, Duration duration) {
    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .sourcePath(Path.of("/media/test.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .duration(duration)
                    .framerate(23.976)
                    .width(1920)
                    .height(1080)
                    .videoCodec("h264")
                    .audioCodec("aac")
                    .bitrate(5_000_000L)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(mode)
                    .videoCodecFamily(container == ContainerFormat.FMP4 ? "av1" : "h264")
                    .audioCodec("aac")
                    .containerFormat(container)
                    .needsKeyframeAlignment(mode != TranscodeMode.FULL_TRANSCODE)
                    .build())
            .options(StreamingOptions.builder().supportedCodecs(List.of("h264", "av1")).build())
            .seekPosition(0)
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .activeRequestCount(new AtomicInteger(0))
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  @Test
  @DisplayName("Should include extra segment when duration has sub-second remainder")
  void shouldIncludeExtraSegmentWhenDurationHasSubSecondRemainder() {
    var session =
        createSessionWithDuration(
            ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(18500));

    var playlist = service.generateMediaPlaylist(session);

    var segmentLines =
        playlist.lines().filter(l -> l.startsWith("segment") && l.endsWith(".ts")).toList();
    assertThat(segmentLines).hasSize(4);
  }

  @Test
  @DisplayName("Should calculate last segment duration when duration has sub-second precision")
  void shouldCalculateLastSegmentDurationWhenDurationHasSubSecondPrecision() {
    var session =
        createSessionWithDuration(
            ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(18500));

    var playlist = service.generateMediaPlaylist(session);

    var extinfLines = playlist.lines().filter(l -> l.startsWith("#EXTINF:")).toList();
    assertThat(extinfLines).hasSize(4);

    var lastExtinf = extinfLines.getLast();
    var duration = Double.parseDouble(lastExtinf.replace("#EXTINF:", "").replace(",", ""));
    assertThat(duration).isCloseTo(0.5, org.assertj.core.api.Assertions.within(0.01));
  }

  // --- Multi-variant (ABR) tests ---

  private StreamSession createAbrSession(int durationSeconds) {
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
                .build(),
            QualityVariant.builder()
                .width(854)
                .height(480)
                .videoBitrate(1_500_000L)
                .audioBitrate(96_000L)
                .label("480p")
                .build());

    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .sourcePath(Path.of("/media/test.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .duration(Duration.ofSeconds(durationSeconds))
                    .framerate(23.976)
                    .width(1920)
                    .height(1080)
                    .videoCodec("hevc")
                    .audioCodec("aac")
                    .bitrate(8_000_000L)
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
            .seekPosition(0)
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .activeRequestCount(new AtomicInteger(0))
            .build();

    for (var variant : variants) {
      session.setVariantHandle(variant.label(), new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    }
    return session;
  }

  @Test
  @DisplayName("Should generate one stream inf per variant when session has multiple variants")
  void shouldGenerateOneStreamInfPerVariantWhenSessionHasMultipleVariants() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    var streamInfLines = playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
    assertThat(streamInfLines).hasSize(3);

    assertThat(streamInfLines.get(0)).contains("RESOLUTION=1920x1080");
    assertThat(streamInfLines.get(1)).contains("RESOLUTION=1280x720");
    assertThat(streamInfLines.get(2)).contains("RESOLUTION=854x480");
  }

  @Test
  @DisplayName("Should include correct bandwidth when session has multiple variants")
  void shouldIncludeCorrectBandwidthWhenSessionHasMultipleVariants() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("BANDWIDTH=5128000");
    assertThat(playlist).contains("BANDWIDTH=3128000");
    assertThat(playlist).contains("BANDWIDTH=1596000");
  }

  @Test
  @DisplayName("Should point each variant to labeled URL when session has multiple variants")
  void shouldPointEachVariantToLabeledUrlWhenSessionHasMultipleVariants() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("1080p/stream.m3u8");
    assertThat(playlist).contains("720p/stream.m3u8");
    assertThat(playlist).contains("480p/stream.m3u8");
  }

  @Test
  @DisplayName("Should keep single variant format when session has no variant list")
  void shouldKeepSingleVariantFormatWhenSessionHasNoVariantList() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    var streamInfLines = playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
    assertThat(streamInfLines).hasSize(1);
    assertThat(playlist).contains("stream.m3u8");
    assertThat(playlist).doesNotContain("/stream.m3u8");
  }

  @Test
  @DisplayName("Should include codecs attribute on each variant when session has multiple variants")
  void shouldIncludeCodecsAttributeOnEachVariantWhenSessionHasMultipleVariants() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    var streamInfLines = playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
    for (var line : streamInfLines) {
      assertThat(line).contains("CODECS=");
    }
  }

  @Test
  @DisplayName("Should reduce segment count when seek position is non-zero")
  void shouldReduceSegmentCountWhenSeekPositionIsNonZero() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);
    session.setSeekPosition(60);

    var playlist = service.generateMediaPlaylist(session);

    var segmentLines =
        playlist.lines().filter(l -> l.startsWith("segment") && l.endsWith(".ts")).toList();
    assertThat(segmentLines).hasSize(10);
  }

  @Test
  @DisplayName("Should generate full segments when seek position is zero")
  void shouldGenerateFullSegmentsWhenSeekPositionIsZero() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMediaPlaylist(session);

    var segmentLines =
        playlist.lines().filter(l -> l.startsWith("segment") && l.endsWith(".ts")).toList();
    assertThat(segmentLines).hasSize(20);
  }
}
