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
class HlsPlaylistServiceTest {

  private HlsPlaylistService service;
  private StreamingProperties properties;

  @BeforeEach
  void setUp() {
    properties = new StreamingProperties(8, 6, 60);
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
            .options(
                StreamingOptions.builder().supportedCodecs(List.of("h264", "av1")).build())
            .seekPosition(0)
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .activeRequestCount(new AtomicInteger(0))
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  @Test
  @DisplayName("Should generate master playlist with EXTM3U first line")
  void shouldGenerateMasterPlaylistWithExtm3uFirstLine() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).startsWith("#EXTM3U\n");
  }

  @Test
  @DisplayName("Should include stream inf with bandwidth and resolution")
  void shouldIncludeStreamInfWithBandwidthAndResolution() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("#EXT-X-STREAM-INF:");
    assertThat(playlist).contains("BANDWIDTH=");
    assertThat(playlist).contains("RESOLUTION=1920x1080");
  }

  @Test
  @DisplayName("Should point to stream playlist URL")
  void shouldPointToStreamPlaylistUrl() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("stream.m3u8");
  }

  @Test
  @DisplayName("Should generate MPEGTS media playlist with version 3")
  void shouldGenerateMpegtsMediaPlaylistWithVersion3() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-VERSION:3");
  }

  @Test
  @DisplayName("Should generate fMP4 media playlist with version 6")
  void shouldGenerateFmp4MediaPlaylistWithVersion6() {
    var session = createSession(ContainerFormat.FMP4, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-VERSION:6");
  }

  @Test
  @DisplayName("Should include EXT-X-MAP for fMP4")
  void shouldIncludeExtXMapForFmp4() {
    var session = createSession(ContainerFormat.FMP4, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-MAP:URI=\"init.mp4\"");
  }

  @Test
  @DisplayName("Should not include EXT-X-MAP for MPEGTS")
  void shouldNotIncludeExtXMapForMpegts() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 60);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).doesNotContain("#EXT-X-MAP");
  }

  @Test
  @DisplayName("Should use .ts extension for MPEGTS segments")
  void shouldUseTsExtensionForMpegtsSegments() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 18);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("segment0.ts");
    assertThat(playlist).contains("segment1.ts");
    assertThat(playlist).contains("segment2.ts");
  }

  @Test
  @DisplayName("Should use .m4s extension for fMP4 segments")
  void shouldUseM4sExtensionForFmp4Segments() {
    var session = createSession(ContainerFormat.FMP4, TranscodeMode.FULL_TRANSCODE, 18);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("segment0.m4s");
  }

  @Test
  @DisplayName("Should include end list")
  void shouldIncludeEndList() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-ENDLIST");
  }

  @Test
  @DisplayName("Should include playlist type VOD")
  void shouldIncludePlaylistTypeVod() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-PLAYLIST-TYPE:VOD");
  }

  @Test
  @DisplayName("Should include target duration")
  void shouldIncludeTargetDuration() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("#EXT-X-TARGETDURATION:6");
  }

  @Test
  @DisplayName("Should calculate correct segment count")
  void shouldCalculateCorrectSegmentCount() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 18);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).contains("segment0.ts");
    assertThat(playlist).contains("segment1.ts");
    assertThat(playlist).contains("segment2.ts");
    assertThat(playlist).doesNotContain("segment3.ts");
  }

  @Test
  @DisplayName("Should start media playlist with EXTM3U")
  void shouldStartMediaPlaylistWithExtm3u() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).startsWith("#EXTM3U\n");
  }

  @Test
  @DisplayName("Should include codecs attribute in master playlist")
  void shouldIncludeCodecsAttributeInMasterPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("CODECS=");
  }

  @Test
  @DisplayName("Should not contain master playlist tags in media playlist")
  void shouldNotContainMasterPlaylistTagsInMediaPlaylist() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMediaPlaylist(session);

    assertThat(playlist).doesNotContain("#EXT-X-STREAM-INF");
  }

  @Test
  @DisplayName("Should handle last segment shorter than target duration")
  void shouldHandleLastSegmentShorterThanTargetDuration() {
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
            .options(
                StreamingOptions.builder().supportedCodecs(List.of("h264", "av1")).build())
            .seekPosition(0)
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .activeRequestCount(new AtomicInteger(0))
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  @Test
  @DisplayName("Should include extra segment for sub-second duration")
  void shouldIncludeExtraSegmentForSubSecondDuration() {
    var session =
        createSessionWithDuration(
            ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(18500));

    var playlist = service.generateMediaPlaylist(session);

    var segmentLines =
        playlist.lines().filter(l -> l.startsWith("segment") && l.endsWith(".ts")).toList();
    assertThat(segmentLines).hasSize(4);
  }

  @Test
  @DisplayName("Should calculate last segment duration with sub-second precision")
  void shouldCalculateLastSegmentDurationWithSubSecondPrecision() {
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
  @DisplayName("Should generate one stream inf per variant sorted by bandwidth descending")
  void shouldGenerateOneStreamInfPerVariantSortedByBandwidthDescending() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    var streamInfLines =
        playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
    assertThat(streamInfLines).hasSize(3);

    assertThat(streamInfLines.get(0)).contains("RESOLUTION=1920x1080");
    assertThat(streamInfLines.get(1)).contains("RESOLUTION=1280x720");
    assertThat(streamInfLines.get(2)).contains("RESOLUTION=854x480");
  }

  @Test
  @DisplayName("Should include correct bandwidth per variant")
  void shouldIncludeCorrectBandwidthPerVariant() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("BANDWIDTH=5128000");
    assertThat(playlist).contains("BANDWIDTH=3128000");
    assertThat(playlist).contains("BANDWIDTH=1596000");
  }

  @Test
  @DisplayName("Should point each variant to labeled playlist URL")
  void shouldPointEachVariantToLabeledPlaylistUrl() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    assertThat(playlist).contains("1080p/stream.m3u8");
    assertThat(playlist).contains("720p/stream.m3u8");
    assertThat(playlist).contains("480p/stream.m3u8");
  }

  @Test
  @DisplayName("Should keep single variant master playlist unchanged")
  void shouldKeepSingleVariantMasterPlaylistUnchanged() {
    var session = createSession(ContainerFormat.MPEGTS, TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMasterPlaylist(session);

    var streamInfLines =
        playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
    assertThat(streamInfLines).hasSize(1);
    assertThat(playlist).contains("stream.m3u8");
    assertThat(playlist).doesNotContain("/stream.m3u8");
  }

  @Test
  @DisplayName("Should include codecs attribute on each variant")
  void shouldIncludeCodecsAttributeOnEachVariant() {
    var session = createAbrSession(120);

    var playlist = service.generateMasterPlaylist(session);

    var streamInfLines =
        playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
    for (var line : streamInfLines) {
      assertThat(line).contains("CODECS=");
    }
  }
}
