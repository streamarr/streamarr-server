package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamMeasurementFixture.concat;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.everySecond;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.repeat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("HLS Playlist Service Tests")
class HlsPlaylistServiceTest {

  private HlsPlaylistService service;
  private StreamingProperties properties;

  @BeforeEach
  void setUp() {
    properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    service = new HlsPlaylistService(properties);
  }

  private StreamSession createSession(TranscodeMode mode, int durationSeconds) {
    return createSessionWithDuration(mode, Duration.ofSeconds(durationSeconds));
  }

  // The default variant of a session without a ladder of its own, at the source's 5 Mb/s.
  private static QualityVariant.QualityVariantBuilder defaultVariantAt(int width, int height) {
    return StreamSessionFixture.defaultVariantBuilder()
        .width(width)
        .height(height)
        .videoBitrate(5_000_000L)
        .label(StreamSession.defaultVariant());
  }

  private static List<QualityVariant> atFrameRate(List<QualityVariant> ladder, Fraction rate) {
    return ladder.stream()
        .map(
            variant ->
                StreamSessionFixture.defaultVariantBuilder()
                    .width(variant.width())
                    .height(variant.height())
                    .videoBitrate(variant.videoBitrate())
                    .label(variant.label())
                    .frameRate(rate)
                    .build())
        .toList();
  }

  private static TranscodeDecision encodedDecision(String videoCodecFamily, Fraction rate) {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily(videoCodecFamily)
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .videoSource(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(rate))
                    .build()))
        .build();
  }

  private StreamSession createSessionWithDuration(TranscodeMode mode, Duration duration) {
    var source = VideoPlaybackFactsFixture.sdrH264FactsBuilder().build();
    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(Path.of("/media/test.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .keyframes(new KeyframeKnowledge.NotReported())
                    .duration(duration)
                    .framerate(OptionalDouble.of(VideoPlaybackFactsFixture.FILM_RATE.toDouble()))
                    .videoPlaybackFacts(Optional.of(source))
                    .width(1920)
                    .height(1080)
                    .videoCodec("h264")
                    .audioCodec("aac")
                    .bitrate(5_000_000L)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(mode)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(Optional.of(source))
                    .build())
            .options(StreamingOptions.builder().build())
            .createdAt(Instant.now())
            .build();
    session.setHandle(StreamSessionFixture.mintHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  private StreamSession createSessionWithAudio(AudioDecision audio, String videoCodecFamily) {
    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(Path.of("/media/test.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .keyframes(new KeyframeKnowledge.NotReported())
                    .duration(Duration.ofSeconds(120))
                    .framerate(OptionalDouble.of(23.976))
                    .width(1920)
                    .height(1080)
                    .videoCodec("h264")
                    .audioCodec("ac3")
                    .bitrate(5_000_000L)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.REMUX)
                    .videoCodecFamily(videoCodecFamily)
                    .audioDecision(audio)
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(
                        Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
                    .build())
            .options(StreamingOptions.builder().build())
            .createdAt(Instant.now())
            .build();
    session.setHandle(StreamSessionFixture.mintHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  private StreamSession createAbrSession(int durationSeconds) {
    var source = VideoPlaybackFactsFixture.sdrH264FactsBuilder().build();
    var variants =
        List.of(
            StreamSessionFixture.defaultVariantBuilder()
                .width(1920)
                .height(1080)
                .videoBitrate(5_000_000L)
                .label("1080p")
                .build(),
            StreamSessionFixture.defaultVariantBuilder()
                .width(1280)
                .height(720)
                .videoBitrate(3_000_000L)
                .label("720p")
                .build(),
            StreamSessionFixture.defaultVariantBuilder()
                .width(854)
                .height(480)
                .videoBitrate(1_500_000L)
                .label("480p")
                .build());

    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(Path.of("/media/test.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .keyframes(new KeyframeKnowledge.NotReported())
                    .duration(Duration.ofSeconds(durationSeconds))
                    .framerate(OptionalDouble.of(VideoPlaybackFactsFixture.FILM_RATE.toDouble()))
                    .videoPlaybackFacts(Optional.of(source))
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
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(Optional.of(source))
                    .build())
            .options(StreamingOptions.builder().build())
            .variants(variants)
            .createdAt(Instant.now())
            .build();

    for (var variant : variants) {
      session.setVariantHandle(
          variant.label(), StreamSessionFixture.mintHandle(1L, TranscodeStatus.ACTIVE));
    }
    return session;
  }

  @Test
  @DisplayName("Should start with EXTM3U when generating multivariant playlist")
  void shouldStartWithExtm3uWhenGeneratingMultivariantPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMultivariantPlaylist(session, "test-token");

    assertThat(playlist).startsWith("#EXTM3U\n");
  }

  @Test
  @DisplayName(
      "Should include stream inf with bandwidth and resolution when generating multivariant"
          + " playlist")
  void shouldIncludeStreamInfWithBandwidthAndResolutionWhenGeneratingMultivariantPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMultivariantPlaylist(session, "test-token");

    assertThat(playlist)
        .contains("#EXT-X-STREAM-INF:")
        .contains("BANDWIDTH=")
        .contains("RESOLUTION=1920x1080");
  }

  @Test
  @DisplayName("Should point to stream playlist URL when generating multivariant playlist")
  void shouldPointToStreamPlaylistUrlWhenGeneratingMultivariantPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);

    var playlist = service.generateMultivariantPlaylist(session, "test-token");

    assertThat(playlist).contains("stream.m3u8?t=test-token");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "#EXT-X-VERSION:6",
        "#EXT-X-MAP:URI=\"init.mp4?t=test-token\"",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        "#EXT-X-TARGETDURATION:6",
        "#EXT-X-ENDLIST"
      })
  @DisplayName("Should include each fixed tag when generating media playlist")
  void shouldIncludeEachFixedTagWhenGeneratingMediaPlaylist(String tag) {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 30);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    assertThat(playlist).contains(tag);
  }

  @Test
  @DisplayName(
      "Should raise the target duration when a stream copy segment prints at the half second")
  void shouldRaiseTheTargetDurationWhenAStreamCopySegmentPrintsAtTheHalfSecond() {
    // In a 1/1,000,000,000 time base, the second keyframe presents at 6.4999995 s.
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000_000_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(
                List.of(
                    new KeyframePosition(0, 0),
                    new KeyframePosition(6_499_999_500L, 6_499_999_500L)))
            .build();
    var session =
        StreamSessionFixture.defaultSessionBuilder()
            .mediaProbe(
                StreamSessionFixture.defaultProbeBuilder()
                    .duration(Duration.ofSeconds(12))
                    .keyframes(new KeyframeKnowledge.Known(positions))
                    .build())
            .build();

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    assertThat(playlist)
        .contains("#EXTINF:6.500000,\n")
        .contains("#EXTINF:5.500001,\n")
        .contains("#EXT-X-TARGETDURATION:7\n");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keyframesThatOpenNoStreamCopy")
  @DisplayName("Should refuse a stream copy playlist when the keyframes cannot open its segments")
  void shouldRefuseAStreamCopyPlaylistWhenTheKeyframesCannotOpenItsSegments(
      String description, KeyframeKnowledge keyframes) {
    var session =
        StreamSessionFixture.defaultSessionBuilder()
            .mediaProbe(StreamSessionFixture.defaultProbeBuilder().keyframes(keyframes).build())
            .build();

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                service.generateMediaPlaylist(
                    session, StreamSession.defaultVariant(), "test-token"));
  }

  static Stream<Arguments> keyframesThatOpenNoStreamCopy() {
    return Stream.of(
        Arguments.of("not reported", new KeyframeKnowledge.NotReported()),
        Arguments.of(
            "a keyframe that presents before the keyframe decoded before it",
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1)
                    .timeBaseDenominator(1_000)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(
                        List.of(new KeyframePosition(6_000, 0), new KeyframePosition(3_000, 1_000)))
                    .build())));
  }

  @Test
  @DisplayName("Should use .m4s extension when generating media playlist")
  void shouldUseM4sExtensionWhenGeneratingMediaPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 18);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    assertThat(playlist)
        .contains("segment0.m4s?t=test-token")
        .contains("segment1.m4s?t=test-token")
        .contains("segment2.m4s?t=test-token");
  }

  @Test
  @DisplayName("Should calculate correct segment count when duration is 18 seconds")
  void shouldCalculateCorrectSegmentCountWhenDurationIs18Seconds() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 18);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    assertThat(playlist)
        .contains("segment0.m4s")
        .contains("segment1.m4s")
        .contains("segment2.m4s")
        .doesNotContain("segment3.m4s");
  }

  @Test
  @DisplayName("Should start media playlist with EXTM3U when generating playlist")
  void shouldStartMediaPlaylistWithExtm3uWhenGeneratingPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 30);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    assertThat(playlist).startsWith("#EXTM3U\n");
  }

  @Test
  @DisplayName("Should include codecs attribute when generating multivariant playlist")
  void shouldIncludeCodecsAttributeWhenGeneratingMultivariantPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 30);

    var playlist = service.generateMultivariantPlaylist(session, "test-token");

    assertThat(playlist).contains("CODECS=");
  }

  @Test
  @DisplayName("Should not contain multivariant playlist tags when generating media playlist")
  void shouldNotContainMultivariantPlaylistTagsWhenGeneratingMediaPlaylist() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 30);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    assertThat(playlist).doesNotContain("#EXT-X-STREAM-INF");
  }

  @Test
  @DisplayName("Should set last segment duration when shorter than target duration")
  void shouldSetLastSegmentDurationWhenShorterThanTargetDuration() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 16);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var lines = playlist.lines().toList();
    var extinfLines = lines.stream().filter(l -> l.startsWith("#EXTINF:")).toList();

    assertThat(extinfLines).hasSize(3);
    var lastExtinf = extinfLines.getLast();
    var duration = Double.parseDouble(lastExtinf.replace("#EXTINF:", "").replace(",", ""));
    assertThat(duration).isLessThanOrEqualTo(6.0);
  }

  @Test
  @DisplayName(
      "Should include an extra segment when the duration runs at least 2 s past the last whole"
          + " target duration")
  void shouldIncludeAnExtraSegmentWhenTheDurationRunsAtLeast2sPastTheLastWholeTargetDuration() {
    var session = createSessionWithDuration(TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(20000));

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var segmentLines =
        playlist
            .lines()
            .filter(l -> l.startsWith("segment") && l.endsWith(".m4s?t=test-token"))
            .toList();
    assertThat(segmentLines).hasSize(4);
  }

  // No output frame of a 2 fps or faster encode, and no audio frame, may start in the final half
  // second, so the last segment takes it in and runs past the target duration.
  @Test
  @DisplayName(
      "Should merge the final half second into the last segment and cover it in the target"
          + " duration when the duration ends less than 2 s into a slot")
  void shouldMergeTheFinalHalfSecondIntoTheLastSegmentWhenTheDurationEndsLessThan2sIntoASlot() {
    var session = createSessionWithDuration(TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(18500));

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var extinfLines = playlist.lines().filter(l -> l.startsWith("#EXTINF:")).toList();
    assertThat(extinfLines).hasSize(3);
    var lastExtinf = extinfLines.getLast();
    var duration = Double.parseDouble(lastExtinf.replace("#EXTINF:", "").replace(",", ""));
    assertThat(duration).isCloseTo(6.5, within(0.001));
    assertThat(playlist).contains("#EXT-X-TARGETDURATION:7");
  }

  @Test
  @DisplayName("Should keep the full segment count when playback advances")
  void shouldKeepTheFullSegmentCountWhenPlaybackAdvances() {
    // The playlist timeline is absolute: 120s / 6s = 20 segments, always.
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);
    session.updatePlaybackState(90, PlaybackState.PLAYING);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var segmentLines =
        playlist
            .lines()
            .filter(l -> l.startsWith("segment") && l.endsWith(".m4s?t=test-token"))
            .toList();
    assertThat(segmentLines).hasSize(20);
  }

  @Test
  @DisplayName("Should generate full segments when seek position is zero")
  void shouldGenerateFullSegmentsWhenSeekPositionIsZero() {
    var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var segmentLines =
        playlist
            .lines()
            .filter(l -> l.startsWith("segment") && l.endsWith(".m4s?t=test-token"))
            .toList();
    assertThat(segmentLines).hasSize(20);
  }

  @Nested
  @DisplayName("Multi-variant ABR")
  class MultiVariantAbrTests {

    @Test
    @DisplayName("Should generate one stream inf per variant when session has multiple variants")
    void shouldGenerateOneStreamInfPerVariantWhenSessionHasMultipleVariants() {
      var session = createAbrSession(120);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      assertThat(streamInfLines).hasSize(3);

      assertThat(streamInfLines.get(0)).contains("RESOLUTION=1920x1080");
      assertThat(streamInfLines.get(1)).contains("RESOLUTION=1280x720");
      assertThat(streamInfLines.get(2)).contains("RESOLUTION=854x480");
    }

    @Test
    @DisplayName("Should include correct bandwidth when session has multiple variants")
    void shouldIncludeCorrectBandwidthWhenSessionHasMultipleVariants() {
      var session = createAbrSession(120);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist)
          .contains("BANDWIDTH=6153600,AVERAGE-BANDWIDTH=5128000")
          .contains("BANDWIDTH=3753600,AVERAGE-BANDWIDTH=3128000")
          .contains("BANDWIDTH=1953600,AVERAGE-BANDWIDTH=1628000");
    }

    @Test
    @DisplayName("Should point each variant to labeled URL when session has multiple variants")
    void shouldPointEachVariantToLabeledUrlWhenSessionHasMultipleVariants() {
      var session = createAbrSession(120);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist)
          .contains("1080p/stream.m3u8?t=test-token")
          .contains("720p/stream.m3u8?t=test-token")
          .contains("480p/stream.m3u8?t=test-token");
    }

    @Test
    @DisplayName("Should keep single variant format when session has no variant list")
    void shouldKeepSingleVariantFormatWhenSessionHasNoVariantList() {
      var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      assertThat(streamInfLines).hasSize(1);
      assertThat(playlist).contains("stream.m3u8").doesNotContain("/stream.m3u8");
    }

    @Test
    @DisplayName(
        "Should include codecs attribute on each variant when session has multiple variants")
    void shouldIncludeCodecsAttributeOnEachVariantWhenSessionHasMultipleVariants() {
      var session = createAbrSession(120);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      for (var line : streamInfLines) {
        assertThat(line).contains("CODECS=");
      }
    }
  }

  @Nested
  @DisplayName("Video codec strings")
  class VideoCodecStringTests {

    @Test
    @DisplayName("Should advertise AV1 level 6.2 when the exact rate exceeds level 6.1's limit")
    void shouldAdvertiseAv1Level62WhenTheExactRateExceedsLevel61sLimit() {
      var rate = new Fraction(1_839_478_091, 26_075_816);
      var source =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(rate))
              .build();
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .mediaProbe(
                  StreamSessionFixture.defaultProbeBuilder()
                      .width(7502)
                      .height(4042)
                      .framerate(OptionalDouble.of(rate.toDouble()))
                      .videoPlaybackFacts(Optional.of(source))
                      .build())
              .transcodeDecision(
                  TranscodeDecision.builder()
                      .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                      .videoCodecFamily("av1")
                      .audioDecision(AudioDecision.stereoAac())
                      .subtitleDecision(SubtitleDecision.exclude())
                      .videoSource(Optional.of(source))
                      .build())
              .variants(List.of(defaultVariantAt(7502, 4042).frameRate(rate).build()))
              .build();

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains("CODECS=\"av01.0.18M.08,mp4a.40.2\"");
    }

    private StreamSession encodedLadderAt(String videoCodecFamily, Fraction frameRate) {
      var ladder = StreamSessionFixture.abrSessionBuilder().build().getVariants();
      return StreamSessionFixture.abrSessionBuilder()
          .mediaProbe(
              StreamSessionFixture.defaultProbeBuilder()
                  .framerate(OptionalDouble.of(frameRate.toDouble()))
                  .videoPlaybackFacts(encodedDecision(videoCodecFamily, frameRate).videoSource())
                  .build())
          .transcodeDecision(encodedDecision(videoCodecFamily, frameRate))
          .variants(atFrameRate(ladder, frameRate))
          .build();
    }

    private static String streamInfFor(String playlist, String variantLabel) {
      var lines = playlist.lines().toList();
      var uri = variantLabel + "/stream.m3u8?t=test-token";
      return lines.get(lines.indexOf(uri) - 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodedLadderCodecCases")
    @DisplayName(
        "Should advertise each encoded variant's own codec string when the ladder encodes the"
            + " video")
    void shouldAdvertiseEachEncodedVariantsOwnCodecStringWhenTheLadderEncodesTheVideo(
        EncodedLadderCodecCase codecCase) {
      var session = encodedLadderAt(codecCase.videoCodecFamily(), codecCase.frameRate());

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(streamInfFor(playlist, "1080p"))
          .contains("CODECS=\"" + codecCase.codecString1080p() + ",mp4a.40.2\"");
      assertThat(streamInfFor(playlist, "720p"))
          .contains("CODECS=\"" + codecCase.codecString720p() + ",mp4a.40.2\"");
    }

    static Stream<EncodedLadderCodecCase> encodedLadderCodecCases() {
      return Stream.of(
          EncodedLadderCodecCase.builder()
              .videoCodecFamily("h264")
              .frameRate(new Fraction(60, 1))
              .codecString1080p("avc1.64002A")
              .codecString720p("avc1.640020")
              .build(),
          EncodedLadderCodecCase.builder()
              .videoCodecFamily("h264")
              .frameRate(VideoPlaybackFactsFixture.FILM_RATE)
              .codecString1080p("avc1.640028")
              .codecString720p("avc1.64001F")
              .build(),
          EncodedLadderCodecCase.builder()
              .videoCodecFamily("av1")
              .frameRate(new Fraction(60, 1))
              .codecString1080p("av01.0.09M.08")
              .codecString720p("av01.0.08M.08")
              .build(),
          EncodedLadderCodecCase.builder()
              .videoCodecFamily("av1")
              .frameRate(VideoPlaybackFactsFixture.FILM_RATE)
              .codecString1080p("av01.0.08M.08")
              .codecString720p("av01.0.05M.08")
              .build());
    }

    @Builder
    record EncodedLadderCodecCase(
        String videoCodecFamily,
        Fraction frameRate,
        String codecString1080p,
        String codecString720p) {

      @Override
      public String toString() {
        return "%s at %s fps → %s and %s"
            .formatted(videoCodecFamily, frameRate, codecString1080p, codecString720p);
      }
    }

    @Test
    @DisplayName(
        "Should advertise the source-sized encode's codec string when a fixed quality encodes")
    void shouldAdvertiseTheSourceSizedEncodesCodecStringWhenAFixedQualityEncodes() {
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .mediaProbe(
                  StreamSessionFixture.defaultProbeBuilder()
                      .framerate(OptionalDouble.of(60))
                      .videoPlaybackFacts(
                          encodedDecision("h264", new Fraction(60, 1)).videoSource())
                      .bitrate(5_000_000L)
                      .build())
              .transcodeDecision(encodedDecision("h264", new Fraction(60, 1)))
              .variants(
                  List.of(defaultVariantAt(1920, 1080).frameRate(new Fraction(60, 1)).build()))
              .build();

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains("CODECS=\"avc1.64002A,mp4a.40.2\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"avc1.4D401F", "hvc1.2.4.L153.B0", "av01.0.13M.10"})
    @DisplayName("Should advertise the probe's codec string when the session copies video")
    void shouldAdvertiseTheProbesCodecStringWhenTheSessionCopiesVideo(String codecString) {
      var session =
          copySession(
              VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                  .codecString(Optional.of(codecString)));

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains("CODECS=\"" + codecString + ",mp4a.40.2\"");
    }

    @Test
    @DisplayName(
        "Should declare an SDR video range when the session copies a Dolby Vision source with an"
            + " SDR base")
    void shouldDeclareAnSdrVideoRangeWhenTheSessionCopiesADolbyVisionSourceWithAnSdrBase() {
      var session =
          copySession(
              VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                  .dynamicRange(VideoPlaybackFactsFixture.dolbyVisionProfile82())
                  .codecString(Optional.of("hvc1.2.4.L120.90")));

      var streamInf =
          service
              .generateMultivariantPlaylist(session, "test-token")
              .lines()
              .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
              .toList();

      assertThat(streamInf)
          .singleElement()
          .satisfies(
              line ->
                  assertThat(line)
                      .contains("CODECS=\"hvc1.2.4.L120.90,mp4a.40.2\"")
                      .contains(",VIDEO-RANGE=SDR")
                      .doesNotContain("SUPPLEMENTAL-CODECS"));
    }

    @ParameterizedTest
    @EnumSource(
        value = CompatibleBase.class,
        names = {"PQ", "HLG"})
    @DisplayName("Should declare the copy's PQ or HLG video range when the session copies it")
    void shouldDeclareTheCopysPqOrHlgVideoRangeWhenTheSessionCopiesIt(CompatibleBase base) {
      var session =
          copySession(
              VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                  .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
                  .codecString(Optional.of("hvc1.2.4.L150.B0")));

      var streamInf =
          service
              .generateMultivariantPlaylist(session, "test-token")
              .lines()
              .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
              .toList();

      assertThat(streamInf)
          .singleElement()
          .satisfies(
              line ->
                  assertThat(line)
                      .contains("CODECS=\"hvc1.2.4.L150.B0,mp4a.40.2\"")
                      .contains(",VIDEO-RANGE=" + base.name())
                      .doesNotContain("SUPPLEMENTAL-CODECS"));
    }

    // ADR 0013: a copy that keeps Dolby Vision on a PQ or HLG base takes CODECS from its base
    // sample entry and announces the Dolby Vision at the decision's level beside it.
    @ParameterizedTest(name = "{0}")
    @MethodSource("dolbyVisionCopies")
    @DisplayName(
        "Should announce the supplemental Dolby Vision codec when the session copies Dolby Vision"
            + " on a PQ or HLG base")
    void shouldAnnounceTheSupplementalDolbyVisionCodecWhenTheSessionCopiesDolbyVisionOnAPqOrHlgBase(
        String scenario, DolbyVisionCopy copy) {
      var session =
          copySession(
              copyDecisionBuilder(
                      VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                          .dynamicRange(dolbyVisionOn(copy.base()))
                          .codecString(Optional.of(copy.codecString())))
                  .videoBitstreamRewrites(List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
                  .videoDolbyVisionOutput(Optional.of(copy.output())));

      assertThat(streamInfLines(session))
          .singleElement()
          .satisfies(
              line ->
                  assertThat(line)
                      .contains("CODECS=\"" + copy.codecString() + ",mp4a.40.2\"")
                      .contains(",VIDEO-RANGE=" + copy.base().name())
                      .contains(",SUPPLEMENTAL-CODECS=\"" + copy.supplementalCodecs() + "\""));
    }

    // ADR 0036: the playlist describes the output after its rewrites.
    @Test
    @DisplayName(
        "Should announce only the PQ range when the session copies profile 8.1 with its Dolby"
            + " Vision stripped")
    void shouldAnnounceOnlyThePqRangeWhenTheSessionCopiesProfile81WithItsDolbyVisionStripped() {
      var session =
          copySession(
              copyDecisionBuilder(
                      VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                          .dynamicRange(dolbyVisionOn(CompatibleBase.PQ))
                          .codecString(Optional.of("hvc1.2.4.L150.B0")))
                  .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_DOLBY_VISION)));

      assertThat(streamInfLines(session))
          .singleElement()
          .asString()
          .contains(",VIDEO-RANGE=PQ")
          .doesNotContain("SUPPLEMENTAL-CODECS");
    }

    static Stream<Arguments> dolbyVisionCopies() {
      return Stream.of(
          Arguments.of(
              "Profile 8.1 at level 6",
              DolbyVisionCopy.builder()
                  .output(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6))
                  .base(CompatibleBase.PQ)
                  .codecString("hvc1.2.4.L150.B0")
                  .supplementalCodecs("dvh1.08.06/db1p")
                  .build()),
          Arguments.of(
              "Profile 8.4 at level 9",
              DolbyVisionCopy.builder()
                  .output(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_4, 9))
                  .base(CompatibleBase.HLG)
                  .codecString("hvc1.2.4.L153.B0")
                  .supplementalCodecs("dvh1.08.09/db4h")
                  .build()),
          Arguments.of(
              "Profile 10.1 at level 13",
              DolbyVisionCopy.builder()
                  .output(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_10_1, 13))
                  .base(CompatibleBase.PQ)
                  .codecString("av01.0.13M.10")
                  .supplementalCodecs("dav1.10.13/db1p")
                  .build()),
          Arguments.of(
              "Profile 10.4 at level 1",
              DolbyVisionCopy.builder()
                  .output(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_10_4, 1))
                  .base(CompatibleBase.HLG)
                  .codecString("av01.0.08M.10")
                  .supplementalCodecs("dav1.10.01/db4h")
                  .build()));
    }

    /** A Dolby Vision copy, its base sample entry's codec string and its supplemental codec. */
    @Builder
    record DolbyVisionCopy(
        DolbyVisionOutput output,
        CompatibleBase base,
        String codecString,
        String supplementalCodecs) {}

    private static VideoDynamicRange dolbyVisionOn(CompatibleBase base) {
      return new VideoDynamicRange(
          base, Presence.ABSENT, new DolbyVision.Present(Optional.empty(), Presence.PRESENT));
    }

    private List<String> streamInfLines(StreamSession session) {
      return service
          .generateMultivariantPlaylist(session, "test-token")
          .lines()
          .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
          .toList();
    }

    // Every encode is SDR until the server encodes HDR (#397), whatever the source's range.
    @Test
    @DisplayName("Should leave the video range out when the session encodes a PQ source")
    void shouldLeaveTheVideoRangeOutWhenTheSessionEncodesAPqSource() {
      var pqSource =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
              .build();
      var decision =
          encodedDecision("h264", new Fraction(24, 1)).toBuilder()
              .videoSource(Optional.of(pqSource))
              .build();
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .transcodeDecision(decision)
              .variants(List.of(defaultVariantAt(1920, 1080).build()))
              .build();

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains("#EXT-X-STREAM-INF:").doesNotContain("VIDEO-RANGE");
    }

    @Test
    @DisplayName(
        "Should leave the video range out when the session copies a source without Dolby Vision")
    void shouldLeaveTheVideoRangeOutWhenTheSessionCopiesASourceWithoutDolbyVision() {
      var session = copySession(VideoPlaybackFactsFixture.sdrH264FactsBuilder());

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).doesNotContain("VIDEO-RANGE");
    }

    private StreamSession copySession(VideoPlaybackFacts.VideoPlaybackFactsBuilder source) {
      return copySession(copyDecisionBuilder(source));
    }

    private StreamSession copySession(TranscodeDecision.TranscodeDecisionBuilder decision) {
      return StreamSessionFixture.defaultSessionBuilder()
          .transcodeDecision(decision.build())
          .build();
    }

    private TranscodeDecision.TranscodeDecisionBuilder copyDecisionBuilder(
        VideoPlaybackFacts.VideoPlaybackFactsBuilder source) {
      return TranscodeDecision.builder()
          .transcodeMode(TranscodeMode.REMUX)
          .videoCodecFamily("hevc")
          .audioDecision(
              AudioDecision.builder()
                  .mode(AudioMode.COPY)
                  .codec("aac")
                  .channels(2)
                  .bitrate(128_000L)
                  .sourceCodecString(Optional.of("mp4a.40.2"))
                  .build())
          .subtitleDecision(SubtitleDecision.exclude())
          .videoSource(Optional.of(source.build()));
    }
  }

  @Nested
  @DisplayName("EXT-X-INDEPENDENT-SEGMENTS")
  class IndependentSegmentsTests {

    private static final String INDEPENDENT_SEGMENTS = "#EXT-X-INDEPENDENT-SEGMENTS";

    private StreamSession encodedLadder(String videoCodecFamily) {
      return StreamSessionFixture.abrSessionBuilder()
          .transcodeDecision(StreamSessionFixture.fullTranscodeDecision(videoCodecFamily))
          .build();
    }

    private String mediaPlaylist(StreamSession session, String variantLabel) {
      return service.generateMediaPlaylist(session, variantLabel, "test-token");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyframeVerifiedEncoders")
    @DisplayName(
        "Should write the tag in the multivariant playlist when every video variant comes from a"
            + " keyframe-verified encoder")
    void shouldWriteTagInMultivariantPlaylistWhenEveryVideoVariantComesFromKeyframeVerifiedEncoder(
        String videoCodecFamily, WorkerVerifiedEncoder encoder) {
      var session = encodedLadder(videoCodecFamily);
      session.pinEncoder("1080p", encoder);
      session.pinEncoder("720p", encoder);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist.lines()).contains(INDEPENDENT_SEGMENTS);
    }

    @Test
    @DisplayName(
        "Should leave the tag out of every media playlist when the multivariant playlist carries"
            + " it")
    void shouldLeaveTagOutOfEveryMediaPlaylistWhenMultivariantPlaylistCarriesIt() {
      var session = encodedLadder("h264");
      session.pinEncoder("1080p", WorkerVerifiedEncoderFixture.libx264());
      session.pinEncoder("720p", WorkerVerifiedEncoderFixture.libx264());

      assertThat(service.generateMultivariantPlaylist(session, "test-token").lines())
          .contains(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "1080p").lines()).doesNotContain(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "720p").lines()).doesNotContain(INDEPENDENT_SEGMENTS);
    }

    static Stream<Arguments> keyframeVerifiedEncoders() {
      return Stream.of(
          Arguments.of("h264", WorkerVerifiedEncoderFixture.libx264()),
          Arguments.of("av1", WorkerVerifiedEncoderFixture.libsvtav1()));
    }

    @Test
    @DisplayName(
        "Should write the tag in the multivariant playlist when the only video variant comes from a"
            + " keyframe-verified encoder")
    void
        shouldWriteTagInMultivariantPlaylistWhenOnlyVideoVariantComesFromKeyframeVerifiedEncoder() {
      var session = createSession(TranscodeMode.FULL_TRANSCODE, 120);
      session.pinEncoder(StreamSession.defaultVariant(), WorkerVerifiedEncoderFixture.libx264());

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist.lines()).contains(INDEPENDENT_SEGMENTS);
    }

    @Test
    @DisplayName(
        "Should write the tag only in the keyframe-verified variant's media playlist when the"
            + " ladder mixes encoders")
    void shouldWriteTagOnlyInKeyframeVerifiedVariantsMediaPlaylistWhenLadderMixesEncoders() {
      var session = encodedLadder("h264");
      session.pinEncoder("1080p", WorkerVerifiedEncoderFixture.libx264());
      session.pinEncoder("720p", WorkerVerifiedEncoderFixture.h264Nvenc());

      assertThat(service.generateMultivariantPlaylist(session, "test-token").lines())
          .doesNotContain(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "1080p").lines()).contains(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "720p").lines()).doesNotContain(INDEPENDENT_SEGMENTS);
    }

    @Test
    @DisplayName(
        "Should write the tag only in the pinned variant's media playlist when another variant has"
            + " no pinned encoder backend")
    void
        shouldWriteTagOnlyInPinnedVariantsMediaPlaylistWhenAnotherVariantHasNoPinnedEncoderBackend() {
      var session = encodedLadder("h264");
      session.pinEncoder("1080p", WorkerVerifiedEncoderFixture.libx264());

      assertThat(service.generateMultivariantPlaylist(session, "test-token").lines())
          .doesNotContain(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "1080p").lines()).contains(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "720p").lines()).doesNotContain(INDEPENDENT_SEGMENTS);
    }

    @Test
    @DisplayName("Should write no tag when no variant comes from a keyframe-verified encoder")
    void shouldWriteNoTagWhenNoVariantComesFromKeyframeVerifiedEncoder() {
      var session = encodedLadder("h264");
      session.pinEncoder("1080p", WorkerVerifiedEncoderFixture.h264Nvenc());
      session.pinEncoder("720p", WorkerVerifiedEncoderFixture.h264Nvenc());

      assertThat(service.generateMultivariantPlaylist(session, "test-token").lines())
          .doesNotContain(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "1080p").lines()).doesNotContain(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, "720p").lines()).doesNotContain(INDEPENDENT_SEGMENTS);
    }

    @Test
    @DisplayName("Should write no tag when the stream session copies the video")
    void shouldWriteNoTagWhenStreamSessionCopiesTheVideo() {
      var session = StreamSessionFixture.defaultSessionBuilder().build();

      assertThat(service.generateMultivariantPlaylist(session, "test-token").lines())
          .doesNotContain(INDEPENDENT_SEGMENTS);
      assertThat(mediaPlaylist(session, StreamSession.defaultVariant()).lines())
          .doesNotContain(INDEPENDENT_SEGMENTS);
    }
  }

  @Nested
  @DisplayName("Audio codec attributes")
  class AudioCodecAttributeTests {

    @ParameterizedTest(name = "{0}")
    @MethodSource("audioPlaylistAttributes")
    @DisplayName(
        "Should include expected audio attribute in multivariant playlist when codec is present")
    void shouldIncludeExpectedAudioAttributeInMultivariantPlaylistWhenCodecIsPresent(
        String scenario, AudioDecision audio, String expectedFragment) {
      var session = createSessionWithAudio(audio, "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains(expectedFragment);
    }

    static Stream<Arguments> audioPlaylistAttributes() {
      return Stream.of(
          Arguments.of(
              "AC-3 codec string",
              AudioDecision.builder()
                  .mode(AudioMode.COPY)
                  .codec("ac3")
                  .channels(6)
                  .bitrate(384_000L)
                  .sourceCodecString(Optional.of("ac-3"))
                  .build(),
              "CODECS=\"avc1.640028,ac-3\""),
          Arguments.of(
              "E-AC-3 codec string",
              AudioDecision.builder()
                  .mode(AudioMode.COPY)
                  .codec("eac3")
                  .channels(6)
                  .bitrate(640_000L)
                  .sourceCodecString(Optional.of("ec-3"))
                  .build(),
              "CODECS=\"avc1.640028,ec-3\""));
    }

    @Test
    @DisplayName("Should not include CHANNELS on STREAM-INF when audio is stereo")
    void shouldNotIncludeChannelsOnStreamInfWhenAudioIsStereo() {
      var audio = AudioDecision.stereoAac();
      var session = createSessionWithAudio(audio, "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      assertThat(streamInfLines).isNotEmpty();
      for (var line : streamInfLines) {
        assertThat(line).doesNotContain("CHANNELS=");
      }
    }

    @Test
    @DisplayName("Should omit audio codec from CODECS attribute when audio mode is none")
    void shouldOmitAudioCodecFromCodecsAttributeWhenAudioModeIsNone() {
      var audio = AudioDecision.none();
      var session = createSessionWithAudio(audio, "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist)
          .contains("CODECS=\"avc1.640028\"")
          .doesNotContain("CODECS=\"avc1.640028,\"");
    }

    @Test
    @DisplayName("Should use AAC codec string when audio is stereo AAC")
    void shouldUseAacCodecStringWhenAudioIsStereoAac() {
      var audio = AudioDecision.stereoAac();
      var session = createSessionWithAudio(audio, "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains("CODECS=\"avc1.640028,mp4a.40.2\"");
    }

    @Test
    @DisplayName("Should use audio decision bitrate for bandwidth when audio is copied")
    void shouldUseAudioDecisionBitrateForBandwidthWhenAudioIsCopied() {
      var source = VideoPlaybackFactsFixture.sdrH264FactsBuilder().build();
      var audio =
          AudioDecision.builder()
              .mode(AudioMode.COPY)
              .codec("ac3")
              .channels(6)
              .bitrate(384_000L)
              .sourceCodecString(Optional.of("ac-3"))
              .build();
      var variant =
          StreamSessionFixture.defaultVariantBuilder()
              .width(1920)
              .height(1080)
              .videoBitrate(5_000_000L)
              .label("1080p")
              .build();

      var session =
          StreamSession.builder()
              .sessionId(UUID.randomUUID())
              .mediaFileId(UUID.randomUUID())
              .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
              .sourcePath(Path.of("/media/test.mkv"))
              .mediaProbe(
                  MediaProbe.builder()
                      .keyframes(new KeyframeKnowledge.NotReported())
                      .duration(Duration.ofSeconds(120))
                      .framerate(OptionalDouble.of(VideoPlaybackFactsFixture.FILM_RATE.toDouble()))
                      .videoPlaybackFacts(Optional.of(source))
                      .width(1920)
                      .height(1080)
                      .videoCodec("hevc")
                      .audioCodec("ac3")
                      .bitrate(8_000_000L)
                      .build())
              .transcodeDecision(
                  TranscodeDecision.builder()
                      .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
                      .videoCodecFamily("h264")
                      .audioDecision(audio)
                      .subtitleDecision(SubtitleDecision.exclude())
                      .videoSource(Optional.of(source))
                      .build())
              .options(StreamingOptions.builder().build())
              .variants(List.of(variant))
              .createdAt(Instant.now())
              .build();
      session.setVariantHandle(
          "1080p", StreamSessionFixture.mintHandle(1L, TranscodeStatus.ACTIVE));

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).contains("BANDWIDTH=6460800,AVERAGE-BANDWIDTH=5384000");
    }
  }

  @Nested
  @DisplayName("EXT-X-MEDIA audio rendition")
  class AudioRenditionTests {

    @Test
    @DisplayName("Should include EXT-X-MEDIA with CHANNELS when audio is stereo")
    void shouldIncludeExtXMediaWithChannelsWhenAudioIsStereo() {
      var session = createSessionWithAudio(AudioDecision.stereoAac(), "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist)
          .contains(
              "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\","
                  + "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\"");
    }

    @Test
    @DisplayName("Should include EXT-X-MEDIA with CHANNELS when audio is surround")
    void shouldIncludeExtXMediaWithChannelsWhenAudioIsSurround() {
      var session =
          createSessionWithAudio(
              AudioDecision.builder()
                  .mode(AudioMode.COPY)
                  .codec("ac3")
                  .channels(6)
                  .bitrate(384_000L)
                  .sourceCodecString(Optional.of("ac-3"))
                  .build(),
              "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist)
          .contains(
              "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\","
                  + "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"6\"");
    }

    @Test
    @DisplayName("Should include AUDIO group reference on STREAM-INF when audio exists")
    void shouldIncludeAudioGroupReferenceOnStreamInfWhenAudioExists() {
      var session = createSessionWithAudio(AudioDecision.stereoAac(), "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      assertThat(streamInfLines).isNotEmpty();
      for (var line : streamInfLines) {
        assertThat(line).contains("AUDIO=\"audio\"");
      }
    }

    @Test
    @DisplayName("Should not include EXT-X-MEDIA when audio mode is none")
    void shouldNotIncludeExtXMediaWhenAudioModeIsNone() {
      var session = createSessionWithAudio(AudioDecision.none(), "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).doesNotContain("#EXT-X-MEDIA:TYPE=AUDIO");
    }

    @Test
    @DisplayName("Should not include AUDIO group reference when audio mode is none")
    void shouldNotIncludeAudioGroupReferenceWhenAudioModeIsNone() {
      var session = createSessionWithAudio(AudioDecision.none(), "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).doesNotContain("AUDIO=\"audio\"");
    }

    @Test
    @DisplayName("Should emit EXT-X-MEDIA before STREAM-INF when audio exists")
    void shouldEmitExtXMediaBeforeStreamInfWhenAudioExists() {
      var session = createSessionWithAudio(AudioDecision.stereoAac(), "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var mediaIndex = playlist.indexOf("#EXT-X-MEDIA:TYPE=AUDIO");
      var streamInfIndex = playlist.indexOf("#EXT-X-STREAM-INF:");
      assertThat(mediaIndex)
          .as("EXT-X-MEDIA tag should be present")
          .isGreaterThanOrEqualTo(0)
          .as("EXT-X-MEDIA should appear before EXT-X-STREAM-INF")
          .isLessThan(streamInfIndex);
    }

    @Test
    @DisplayName("Should not include CHANNELS on STREAM-INF when audio is surround")
    void shouldNotIncludeChannelsOnStreamInfWhenAudioIsSurround() {
      var session =
          createSessionWithAudio(
              AudioDecision.builder()
                  .mode(AudioMode.COPY)
                  .codec("ac3")
                  .channels(6)
                  .bitrate(384_000L)
                  .sourceCodecString(Optional.of("ac-3"))
                  .build(),
              "h264");

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      assertThat(streamInfLines).isNotEmpty();
      for (var line : streamInfLines) {
        assertThat(line).doesNotContain("CHANNELS=");
      }
    }

    @Test
    @DisplayName("Should include AUDIO group reference on all variants when ABR session has audio")
    void shouldIncludeAudioGroupReferenceOnAllVariantsWhenAbrSessionHasAudio() {
      var session = createAbrSession(120);

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      var streamInfLines =
          playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:")).toList();
      assertThat(streamInfLines).hasSize(3);
      for (var line : streamInfLines) {
        assertThat(line).contains("AUDIO=\"audio\"");
      }
    }
  }

  @Nested
  @DisplayName("Displayed size and frame rate")
  class DisplayedSizeAndFrameRateTests {

    @Test
    @DisplayName(
        "Should advertise the rotated size when a session without a ladder copies an upright phone"
            + " recording")
    void shouldAdvertiseTheRotatedSizeWhenASessionWithoutALadderCopiesAnUprightPhoneRecording() {
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .mediaProbe(
                  probeWithFacts(
                      VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                          .streamIndex(0)
                          .rotationDegrees(OptionalInt.of(-90))))
              .build();

      assertThat(streamInf(session)).singleElement().asString().contains(",RESOLUTION=1080x1920,");
    }

    @Test
    @DisplayName(
        "Should advertise the cropped size when a session without a ladder copies a cropped source")
    void shouldAdvertiseTheCroppedSizeWhenASessionWithoutALadderCopiesACroppedSource() {
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .mediaProbe(
                  probeWithFacts(
                      VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                          .streamIndex(0)
                          .containerCropping(
                              Optional.of(
                                  ContainerCropping.builder().top(140).bottom(140).build()))))
              .build();

      assertThat(streamInf(session)).singleElement().asString().contains(",RESOLUTION=1920x800,");
    }

    @ParameterizedTest(name = "{1} fps")
    @MethodSource("outputFrameRates")
    @DisplayName(
        "Should advertise the output frame rate to three decimals when every variant encodes")
    void shouldAdvertiseTheOutputFrameRateToThreeDecimalsWhenEveryVariantEncodes(
        Fraction rate, String frameRate) {
      var decision = encodedDecision("h264", rate);
      var ladder = StreamSessionFixture.defaultLadderSessionBuilder().build().getVariants();
      var session =
          StreamSessionFixture.defaultLadderSessionBuilder()
              .mediaProbe(
                  StreamSessionFixture.defaultProbeBuilder()
                      .framerate(OptionalDouble.of(rate.toDouble()))
                      .videoPlaybackFacts(decision.videoSource())
                      .build())
              .transcodeDecision(decision)
              .variants(atFrameRate(ladder, rate))
              .build();

      assertThat(streamInf(session))
          .hasSize(4)
          .allSatisfy(line -> assertThat(line).contains(",FRAME-RATE=" + frameRate));
    }

    // RFC 8216 section 4.3.4.2 gives FRAME-RATE to three decimals.
    static Stream<Arguments> outputFrameRates() {
      return Stream.of(
          Arguments.of(new Fraction(24_000, 1_001), "23.976"),
          Arguments.of(new Fraction(30_000, 1_001), "29.970"),
          Arguments.of(new Fraction(60, 1), "60.000"),
          Arguments.of(new Fraction(25, 1), "25.000"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copiedFrameRates")
    @DisplayName(
        "Should advertise the copied frame rate to three decimals when the session copies the"
            + " video")
    void shouldAdvertiseTheCopiedFrameRateToThreeDecimalsWhenTheSessionCopiesTheVideo(
        String rateSource, FrameRateRule rule, String frameRate) {
      assertThat(streamInf(copySessionWithRule(rule)))
          .singleElement()
          .asString()
          .contains(",FRAME-RATE=" + frameRate);
    }

    static Stream<Arguments> copiedFrameRates() {
      return Stream.of(
          Arguments.of(
              "declared rates that agree",
              VideoPlaybackFactsFixture.declaredRateRule(new Fraction(25, 1)),
              "25.000"),
          Arguments.of(
              "declared NTSC rates that agree",
              VideoPlaybackFactsFixture.declaredRateRule(new Fraction(30_000, 1_001)),
              "29.970"),
          Arguments.of(
              "packet scan whose motion rate snaps to a listed rate",
              VideoPlaybackFactsFixture.packetScanRule(59.94005994, new Fraction(60, 1)),
              "59.940"),
          Arguments.of(
              "declared-rate fallback",
              VideoPlaybackFactsFixture.declaredRateFallbackRule(
                  FrameRateRuleInputs.builder()
                      .averageRate(Optional.of(new Fraction(75, 2)))
                      .build(),
                  new Fraction(30, 1)),
              "37.500"));
    }

    // RFC 8216 section 4.3.4.2: FRAME-RATE is the variant's maximum frame rate.
    @Test
    @DisplayName(
        "Should advertise the maximum frame rate when the session copies a variable-rate stream"
            + " whose maximum the probe stored")
    void shouldAdvertiseTheMaximumFrameRateWhenTheSessionCopiesAVariableRateStreamWithAMaximum() {
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .streamIndex(0)
              .frameRateRule(VideoPlaybackFactsFixture.packetScanRule(24, new Fraction(24, 1)))
              .maximumFrameRate(Optional.of(new Fraction(60_000, 1_001)));
      var session = StreamSessionFixture.copySessionBuilder(probeWithFacts(facts)).build();

      assertThat(streamInf(session)).singleElement().asString().contains(",FRAME-RATE=59.940");
    }

    @Test
    @DisplayName(
        "Should advertise the copied frame rate when the session copies a variable-rate stream"
            + " whose maximum is unknown")
    void shouldAdvertiseTheCopiedFrameRateWhenTheSessionCopiesAVariableRateStreamWithoutAMaximum() {
      var session =
          copySessionWithRule(VideoPlaybackFactsFixture.packetScanRule(24, new Fraction(24, 1)));

      assertThat(streamInf(session)).singleElement().asString().contains(",FRAME-RATE=24.000");
    }

    @Test
    @DisplayName(
        "Should advertise no frame rate when the session copies a stream whose frame rate the rule"
            + " refused")
    void shouldAdvertiseNoFrameRateWhenTheSessionCopiesAStreamWhoseFrameRateTheRuleRefused() {
      var session = copySessionWithRule(VideoPlaybackFactsFixture.refusedRule());

      assertThat(streamInf(session)).singleElement().asString().doesNotContain("FRAME-RATE");
    }

    // ADR 0013: no encode can replace a copy whose frame rate is unknown (#446), so the HDR copy
    // still announces its range.
    @Test
    @DisplayName(
        "Should announce the PQ range without a frame rate when the session copies a PQ stream"
            + " whose frame rate the rule refused")
    void
        shouldAnnounceThePqRangeWithoutAFrameRateWhenTheSessionCopiesAPqStreamWhoseFrameRateTheRuleRefused() {
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .streamIndex(0)
              .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
              .frameRateRule(VideoPlaybackFactsFixture.refusedRule());
      var session = StreamSessionFixture.copySessionBuilder(probeWithFacts(facts)).build();

      assertThat(streamInf(session))
          .singleElement()
          .asString()
          .contains(",VIDEO-RANGE=PQ")
          .doesNotContain("FRAME-RATE");
    }

    private StreamSession copySessionWithRule(FrameRateRule rule) {
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder().streamIndex(0).frameRateRule(rule);
      return StreamSessionFixture.copySessionBuilder(probeWithFacts(facts)).build();
    }

    private static MediaProbe probeWithFacts(VideoPlaybackFacts.VideoPlaybackFactsBuilder facts) {
      return StreamSessionFixture.defaultProbeBuilder()
          .videoPlaybackFacts(Optional.of(facts.build()))
          .build();
    }

    private List<String> streamInf(StreamSession session) {
      return service
          .generateMultivariantPlaylist(session, "test-token")
          .lines()
          .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
          .toList();
    }
  }

  // ADR 0050: a copy costs its own video bitrate plus the stream session's audio bitrate. For a
  // copied audio track that is the track's bitrate; for an encoded one, the encode's bitrate.
  @Nested
  @DisplayName("Bandwidth of a video copy")
  class CopyBandwidthTests {

    // The default probe's video is stream 0, so its first audio stream is stream 1.
    private static final int MEASURED_AUDIO = 1;

    private static final AudioDecision AC3_SURROUND_COPY =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("ac3")
            .channels(6)
            .bitrate(640_000L)
            .sourceCodecString(Optional.of("ac-3"))
            .build();

    // The bitrate the audio decision takes for a 5.1 FLAC track whose bitrate the probe does not
    // know. A lossless track runs far above it.
    private static final AudioDecision FLAC_SURROUND_COPY =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("flac")
            .channels(6)
            .bitrate(384_000L)
            .sourceCodecString(Optional.of("fLaC"))
            .build();

    // An 8 Mb/s container also holds audio tracks that the session does not deliver, and 0 is a
    // container bitrate that the probe does not know.
    @ParameterizedTest(name = "container bitrate {0}")
    @ValueSource(longs = {5_000_000L, 8_000_000L, 0L})
    @DisplayName(
        "Should advertise the copied stream's bitrate plus the copied audio track's bitrate when the"
            + " probe knows both")
    void shouldAdvertiseTheCopiedStreamsBitratePlusTheCopiedAudioTracksBitrateWhenTheProbeKnowsBoth(
        long containerBitrate) {
      var probe =
          withVideoAndAudioStreams(
              ac3SurroundProbeBuilder().bitrate(containerBitrate), OptionalLong.of(4_500_000L));

      var playlist =
          service.generateMultivariantPlaylist(
              copySession(probe, remuxDecisionCopying(AC3_SURROUND_COPY)), "test-token");

      assertThat(playlist).contains("BANDWIDTH=6168000,AVERAGE-BANDWIDTH=5140000,");
    }

    // The 5 Mb/s container bitrate stands in for the copied video and already holds the copied AC-3
    // track, so the track adds nothing to it.
    @Test
    @DisplayName(
        "Should advertise the container bitrate alone when the session copies an audio track and the"
            + " probe does not know the copied stream's bitrate")
    void
        shouldAdvertiseTheContainerBitrateAloneWhenTheSessionCopiesAnAudioTrackAndTheProbeDoesNotKnowTheCopiedStreamsBitrate() {
      var probe =
          withVideoAndAudioStreams(
              ac3SurroundProbeBuilder().bitrate(5_000_000L), OptionalLong.empty());

      var playlist =
          service.generateMultivariantPlaylist(
              copySession(probe, remuxDecisionCopying(AC3_SURROUND_COPY)), "test-token");

      assertThat(playlist).contains("BANDWIDTH=6000000,AVERAGE-BANDWIDTH=5000000,");
    }

    @Test
    @DisplayName(
        "Should advertise the copied audio track's bitrate alone when the probe knows neither the"
            + " copied stream's bitrate nor the container bitrate")
    void
        shouldAdvertiseTheCopiedAudioTracksBitrateAloneWhenTheProbeKnowsNeitherTheCopiedStreamsBitrateNorTheContainerBitrate() {
      var probe =
          withVideoAndAudioStreams(ac3SurroundProbeBuilder().bitrate(0L), OptionalLong.empty());

      var playlist =
          service.generateMultivariantPlaylist(
              copySession(probe, remuxDecisionCopying(AC3_SURROUND_COPY)), "test-token");

      assertThat(playlist).contains("BANDWIDTH=768000,AVERAGE-BANDWIDTH=640000,");
    }

    @Test
    @DisplayName(
        "Should advertise the copied stream's bitrate when the source holds another video stream"
            + " first")
    void shouldAdvertiseTheCopiedStreamsBitrateWhenTheSourceHoldsAnotherVideoStreamFirst() {
      var facts = VideoPlaybackFactsFixture.sdrH264FactsBuilder().streamIndex(1).build();
      var probe = ac3SurroundProbeBuilder().videoPlaybackFacts(Optional.of(facts));
      var source = probe.build();
      var streams =
          List.of(
              ProbeFixture.videoStreamBuilder(source)
                  .index(0)
                  .bitrate(OptionalLong.of(1_000_000L))
                  .build(),
              ProbeFixture.videoStreamBuilder(source).bitrate(OptionalLong.of(4_500_000L)).build(),
              ProbeFixture.audioStreamBuilder("ac3")
                  .index(2)
                  .channels(source.audioChannels())
                  .bitrate(source.audioBitrate())
                  .build());

      var playlist =
          service.generateMultivariantPlaylist(
              copySession(probe.streams(streams).build(), remuxDecisionCopying(AC3_SURROUND_COPY)),
              "test-token");

      assertThat(playlist).contains("BANDWIDTH=6168000,AVERAGE-BANDWIDTH=5140000,");
    }

    // The 8 Mb/s container holds the 4.5 Mb/s copied video and the FLAC track that the session
    // copies, so the FLAC track runs at up to 3.5 Mb/s, not at its 384 kb/s nominal bitrate.
    @Test
    @DisplayName(
        "Should advertise the container bitrate when the session copies an audio track whose"
            + " bitrate the probe does not know and the container bitrate exceeds the nominal sum")
    void
        shouldAdvertiseTheContainerBitrateWhenTheSessionCopiesAnAudioTrackWhoseBitrateTheProbeDoesNotKnowAndTheContainerBitrateExceedsTheNominalSum() {
      var probe =
          withVideoAndAudioStreams(
              flacSurroundProbeBuilder().bitrate(8_000_000L), OptionalLong.of(4_500_000L));

      var playlist =
          service.generateMultivariantPlaylist(
              copySession(probe, remuxDecisionCopying(FLAC_SURROUND_COPY)), "test-token");

      assertThat(playlist).contains("BANDWIDTH=9600000,AVERAGE-BANDWIDTH=8000000,");
    }

    @ParameterizedTest(name = "container bitrate {0}")
    @ValueSource(longs = {4_000_000L, 0L})
    @DisplayName(
        "Should advertise the copied stream's bitrate plus the audio track's nominal bitrate when"
            + " the session copies an audio track whose bitrate the probe does not know and the"
            + " container bitrate is lower or unknown")
    void
        shouldAdvertiseTheCopiedStreamsBitratePlusTheAudioTracksNominalBitrateWhenTheSessionCopiesAnAudioTrackWhoseBitrateTheProbeDoesNotKnowAndTheContainerBitrateIsLowerOrUnknown(
            long containerBitrate) {
      var probe =
          withVideoAndAudioStreams(
              flacSurroundProbeBuilder().bitrate(containerBitrate), OptionalLong.of(4_500_000L));

      var playlist =
          service.generateMultivariantPlaylist(
              copySession(probe, remuxDecisionCopying(FLAC_SURROUND_COPY)), "test-token");

      assertThat(playlist).contains("BANDWIDTH=5860800,AVERAGE-BANDWIDTH=4884000,");
    }

    // The session delivers the encode, whose bitrate is known, so the container bitrate never
    // stands in for the audio.
    @Test
    @DisplayName(
        "Should advertise the copied stream's bitrate plus the encoded audio's bitrate when the"
            + " session encodes an audio track whose bitrate the probe does not know")
    void
        shouldAdvertiseTheCopiedStreamsBitratePlusTheEncodedAudiosBitrateWhenTheSessionEncodesAnAudioTrackWhoseBitrateTheProbeDoesNotKnow() {
      var probe =
          withVideoAndAudioStreams(
              flacSurroundProbeBuilder().bitrate(8_000_000L), OptionalLong.of(4_500_000L));
      var decision =
          StreamSessionFixture.remuxDecisionBuilder()
              .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
              .audioDecision(AudioDecision.stereoAac());

      var playlist =
          service.generateMultivariantPlaylist(copySession(probe, decision), "test-token");

      assertThat(playlist).contains("BANDWIDTH=5553600,AVERAGE-BANDWIDTH=4628000,");
    }

    // ADR 0054: over the 2-hour default media playlist, 4,050,000,000 measured video bytes average
    // 4.5 Mb/s and 576,000,000 AC-3 bytes 640 kb/s, below the probed 6 Mb/s video and the 8 Mb/s
    // container. The 1,200 segments add 192,032 bytes of framing, 214 b/s: 32 bytes each, 64 bytes
    // for each track in each, and 16 bytes for each track's one packet.
    @Test
    @DisplayName(
        "Should advertise the copied video's measured average plus the copied audio's average"
            + " when the server holds a measurement of the source's streams")
    void shouldAdvertiseMeasuredVideoAveragePlusCopiedAudioAverageWhenServerHoldsMeasurement() {
      var playlist =
          multivariantOfMeasuredAc3Copy(
              StreamMeasurementFixture.measuredStreamBuilder(MEASURED_AUDIO, 576_000_000L)
                  .measuredDuration(7_200_000)
                  .build());

      assertThat(playlist).contains(",AVERAGE-BANDWIDTH=5140214,");
    }

    // ADR 0054: 288,000,000 AC-3 bytes that end after 1 hour average 320 kb/s over the 2-hour
    // playlist, half the track's own 640 kb/s. The track's 64 bytes still count in all 1,200
    // segments.
    @Test
    @DisplayName(
        "Should advertise a copied audio track that ends early at its average over the media"
            + " playlist when the server holds a measurement of the source's streams")
    void shouldAdvertiseCopiedAudioTrackThatEndsEarlyAtItsAverageOverMediaPlaylist() {
      var playlist =
          multivariantOfMeasuredAc3Copy(
              StreamMeasurementFixture.measuredStreamBuilder(MEASURED_AUDIO, 288_000_000L)
                  .measuredDuration(3_600_000)
                  .build());

      assertThat(playlist).contains(",AVERAGE-BANDWIDTH=4820214,");
    }

    // A stream copy segment opens at each keyframe of a 1/10,000,000 time base, at 6.0000004 s and
    // 12.0000008 s, so the media playlist writes EXTINF durations of 6.000000, 6.000000 and
    // 5.999999 s, 17.999999 s in all. 2,246,256 video bytes in 216 packets and 3,744 bytes of
    // framing are 18,000,000 bits, which average 1,000,000.06 b/s over those durations.
    @Test
    @DisplayName(
        "Should advertise a measured copy's average over the EXTINF durations that its media"
            + " playlist writes")
    void shouldAdvertiseMeasuredCopysAverageOverExtinfDurationsThatItsMediaPlaylistWrites() {
      var positions =
          KeyframePositions.builder()
              .streamIndex(0)
              .timeBaseNumerator(1)
              .timeBaseDenominator(10_000_000)
              .source(KeyframePositionSource.CONTAINER_INDEX)
              .keyframes(
                  List.of(
                      new KeyframePosition(0, 0),
                      new KeyframePosition(60_000_004L, 60_000_004L),
                      new KeyframePosition(120_000_008L, 120_000_008L)))
              .build();
      var probe =
          StreamSessionFixture.defaultProbeBuilder()
              .duration(Duration.ofSeconds(18))
              .keyframes(new KeyframeKnowledge.Known(positions));
      var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
      var measurement =
          new StreamMeasurements(
              StreamMeasurementSource.SAMPLE_TABLE,
              List.of(
                  StreamMeasurementFixture.measuredStreamBuilder(video.index(), 2_246_256L)
                      .packetCount(216)
                      .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(216))
                      .measuredDuration(18_000)
                      .build()));
      var mediaProbe =
          probe.streams(List.of(video)).streamMeasurements(Optional.of(measurement)).build();
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .mediaProbe(mediaProbe)
              .transcodeDecision(
                  StreamSessionFixture.remuxDecisionBuilder()
                      .videoSource(mediaProbe.videoPlaybackFacts())
                      .audioDecision(AudioDecision.none())
                      .build())
              .build();

      var multivariant = service.generateMultivariantPlaylist(session, "test-token");
      var media =
          service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

      assertThat(media).contains("#EXTINF:6.000000,\n").contains("#EXTINF:5.999999,\n");
      assertThat(multivariant).contains("AVERAGE-BANDWIDTH=1000001,");
    }

    // A source of 6 Mb/s probed video in an 8 Mb/s container beside an AC-3 track, whose video
    // the server measured at 4,050,000,000 bytes over 2 hours beside the given audio measurement,
    // in a session that copies the AC-3 track.
    private String multivariantOfMeasuredAc3Copy(MeasuredStream audio) {
      var probe =
          StreamSessionFixture.defaultProbeBuilder()
              .bitrate(8_000_000L)
              .audioCodec("ac3")
              .audioBitrate(OptionalLong.of(640_000L));
      var video =
          ProbeFixture.videoStreamBuilder(probe.build())
              .bitrate(OptionalLong.of(6_000_000L))
              .build();
      var audioStream = ProbeFixture.audioStreamBuilder("ac3").index(audio.streamIndex()).build();
      var measurement =
          new StreamMeasurements(
              StreamMeasurementSource.SAMPLE_TABLE,
              List.of(
                  StreamMeasurementFixture.measuredStreamBuilder(video.index(), 4_050_000_000L)
                      .measuredDuration(7_200_000)
                      .build(),
                  audio));
      var source =
          probe
              .streams(List.of(video, audioStream))
              .streamMeasurements(Optional.of(measurement))
              .build();

      return service.generateMultivariantPlaylist(
          copySession(source, remuxDecisionCopying(AC3_SURROUND_COPY)), "test-token");
    }

    private static MediaProbe.MediaProbeBuilder ac3SurroundProbeBuilder() {
      return StreamSessionFixture.defaultProbeBuilder()
          .audioCodec("ac3")
          .audioChannels(OptionalInt.of(6))
          .audioBitrate(OptionalLong.of(640_000L));
    }

    private static MediaProbe.MediaProbeBuilder flacSurroundProbeBuilder() {
      return StreamSessionFixture.defaultProbeBuilder()
          .audioCodec("flac")
          .audioChannels(OptionalInt.of(6))
          .audioBitrate(OptionalLong.empty());
    }

    // Lists the probe's video stream at the given bitrate, then its audio stream.
    private static MediaProbe withVideoAndAudioStreams(
        MediaProbe.MediaProbeBuilder probe, OptionalLong videoBitrate) {
      var source = probe.build();
      var video = ProbeFixture.videoStreamBuilder(source).bitrate(videoBitrate).build();
      var audio =
          ProbeFixture.audioStreamBuilder(source.audioCodec())
              .index(video.index() + 1)
              .channels(source.audioChannels())
              .bitrate(source.audioBitrate())
              .build();
      return probe.streams(List.of(video, audio)).build();
    }

    private static TranscodeDecision.TranscodeDecisionBuilder remuxDecisionCopying(
        AudioDecision audio) {
      return StreamSessionFixture.remuxDecisionBuilder().audioDecision(audio);
    }

    private static StreamSession copySession(
        MediaProbe probe, TranscodeDecision.TranscodeDecisionBuilder decision) {
      return StreamSessionFixture.defaultSessionBuilder()
          .mediaProbe(probe)
          .transcodeDecision(decision.videoSource(probe.videoPlaybackFacts()).build())
          .build();
    }
  }

  @Nested
  @DisplayName("Peak bandwidth of a measured video copy")
  class MeasuredCopyPeakTests {

    private static final AudioDecision AC3_COPY =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("ac3")
            .channels(6)
            .bitrate(640_000L)
            .sourceCodecString(Optional.of("ac-3"))
            .build();

    // RFC 8216 §4.1 over 6 s segments: a run lasts 3 to 9 s, so each run is one segment. Segment 2
    // holds 6 video intervals of 150,000 bytes, three times the others: 900,000 bytes plus 32 + 64
    // bytes and 16 for each of its 144 samples, 902,400 bytes, 1,203,200 b/s. Each AC-3 interval
    // holds 80,000 bytes in 32 frames of 32 ms. A middle segment's audio spans its own 6 intervals
    // and, widened by one frame, one neighbouring interval on each side: 8 × (80,000 + 16 × 32)
    // + 64 = 644,160 bytes, 858,880 b/s. The copy averages 1,180,715 b/s.
    @Test
    @DisplayName(
        "Should advertise the measured video peak plus the copied audio peak when one segment holds"
            + " three times the video of the others")
    void
        shouldAdvertiseMeasuredVideoPeakPlusCopiedAudioPeakWhenOneSegmentHoldsThreeTimesTheVideo() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(everySecond(36))
              .videoBytes(concat(repeat(12, 50_000L), repeat(6, 150_000L), repeat(18, 50_000L)))
              .audioBytes(repeat(36, 80_000L))
              .audioPackets(repeat(36, 32L))
              .build();

      var playlist = service.generateMultivariantPlaylist(copySession(source, AC3_COPY), "t");

      assertThat(playlist).contains("BANDWIDTH=2062080,AVERAGE-BANDWIDTH=1180715,");
    }

    // One measurement serves any segment length. On 6 s segments, segment 2 holds the 3 dense
    // intervals of 150,000 bytes beside 3 of 50,000: 602,400 bytes with framing, 803,200 b/s. On
    // 3 s segments, segment 4 holds only the dense intervals: 451,248 bytes, 1,203,328 b/s. Each
    // run is one segment, since two segments last longer than 1.5 target durations.
    @ParameterizedTest(name = "{0}")
    @MethodSource("segmentLengthsThatOneMeasurementServes")
    @DisplayName(
        "Should follow the session's segment length when one measurement serves 6 and 3 second"
            + " segments")
    void shouldFollowTheSessionsSegmentLengthWhenOneMeasurementServesSixAndThreeSecondSegments(
        SegmentLengthPeak expected) {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(everySecond(36))
              .videoBytes(concat(repeat(12, 50_000L), repeat(3, 150_000L), repeat(21, 50_000L)))
              .build();
      var segmentService =
          new HlsPlaylistService(
              StreamingProperties.builder()
                  .targetSegmentDuration(Duration.ofSeconds(expected.segmentSeconds()))
                  .build());

      var playlist =
          segmentService.generateMultivariantPlaylist(
              copySession(source, AudioDecision.none()), "t");

      assertThat(playlist).contains(expected.bandwidthAttributes());
    }

    static Stream<SegmentLengthPeak> segmentLengthsThatOneMeasurementServes() {
      return Stream.of(
          SegmentLengthPeak.builder()
              .segmentSeconds(6)
              .bandwidthAttributes("BANDWIDTH=803200,AVERAGE-BANDWIDTH=469867,")
              .build(),
          SegmentLengthPeak.builder()
              .segmentSeconds(3)
              .bandwidthAttributes("BANDWIDTH=1203328,AVERAGE-BANDWIDTH=469995,")
              .build());
    }

    @Builder
    record SegmentLengthPeak(int segmentSeconds, String bandwidthAttributes) {}

    // With four-second keyframe intervals, the 6 s grid merges to 8/4/8/4/8/4 s and the
    // 3 s grid merges to nine 4 s segments. Each synthetic interval holds 24 samples. At 6 s
    // the dense interval shares its segment with a sparse one: 700,000 payload bytes plus
    // 96 + 48 * 16 framing bytes over 8 s. At 3 s it stands alone: 600,480 bytes over 4 s.
    @ParameterizedTest(name = "{0} second slots")
    @CsvSource({"6, 700864", "3, 1200960"})
    @DisplayName(
        "Should recompute the peak for both merged segment lists when one measurement serves"
            + " six and three second slots")
    void shouldRecomputePeakForBothMergedSegmentListsWhenOneMeasurementServesSixAndThreeSecondSlots(
        int slotSeconds, long expectedBandwidth) {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(
                  List.of(0L, 4_000L, 8_000L, 12_000L, 16_000L, 20_000L, 24_000L, 28_000L, 32_000L))
              .videoBytes(concat(repeat(3, 100_000L), repeat(1, 600_000L), repeat(5, 100_000L)))
              .build();
      var segmentService =
          new HlsPlaylistService(
              StreamingProperties.builder()
                  .targetSegmentDuration(Duration.ofSeconds(slotSeconds))
                  .build());

      assertThat(
              segmentService.generateMultivariantPlaylist(
                  copySession(source, AudioDecision.none()), "t"))
          .contains("BANDWIDTH=" + expectedBandwidth + ",");
    }

    // RFC 8216 §4.1: a run lasts 3 to 9 s under a 6 s target. The last segment lasts 2.5 s, as the
    // last keyframe presents 2.5 s before the media end, and holds 500,000 bytes, so it counts only
    // with the 6 s segment before it: 302,400 + 500,480 bytes over 8.5 s, 755,651.8 b/s. Alone it
    // would advertise 1,601,536 b/s.
    @Test
    @DisplayName(
        "Should count a segment shorter than half the target duration only with its neighbour when"
            + " it ends the playlist")
    void shouldCountASegmentShorterThanHalfTheTargetOnlyWithItsNeighbourWhenItEndsThePlaylist() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofMillis(38_500))
              .keyframeMillis(everySecond(37))
              .videoBytes(concat(repeat(36, 50_000L), repeat(1, 500_000L)))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.none()), "t");

      assertThat(playlist).contains("BANDWIDTH=755652,AVERAGE-BANDWIDTH=481015,");
    }

    // Keyframes 10 s apart leave the 6 s slots [12 s, 18 s) and [24 s, 30 s) without one, so the
    // list holds segments of 10, 10, 10 and 6 s under a 10 s target. A run lasts 5 to 15 s, so each
    // segment is a run of its own: segment 1 holds 400,000 bytes and 480 bytes of framing over 10
    // s, 320,384 b/s.
    @Test
    @DisplayName(
        "Should take each merged segment's size from the segment list when it measures the peak")
    void shouldTakeEachMergedSegmentsSizeFromTheSegmentListWhenItMeasuresThePeak() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(List.of(0L, 10_000L, 20_000L, 30_000L))
              .videoBytes(List.of(100_000L, 400_000L, 100_000L, 60_000L))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.none()), "t");

      assertThat(playlist).contains("BANDWIDTH=320384,AVERAGE-BANDWIDTH=147094,");
    }

    // The keyframe at -2 s opens no segment, so segment 0 holds its interval of 600,000 bytes with
    // the three that follow: 901,632 bytes with framing, 1,202,176 b/s.
    @Test
    @DisplayName(
        "Should count the interval of a keyframe that presents before zero in segment 0 when the"
            + " source starts before its first segment")
    void shouldCountIntervalOfKeyframeThatPresentsBeforeZeroInSegmentZeroWhenSourceStartsEarly() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(12))
              .keyframeMillis(List.of(-2_000L, 0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L))
              .videoBytes(concat(repeat(1, 600_000L), repeat(6, 100_000L)))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.none()), "t");

      assertThat(playlist).contains("BANDWIDTH=1202176,AVERAGE-BANDWIDTH=801920,");
    }

    // The keyframe at the 12 s media end opens no segment, so the last segment holds its interval
    // of 600,000 bytes with the three before it: 901,632 bytes with framing, 1,202,176 b/s.
    @Test
    @DisplayName(
        "Should count the interval of a keyframe at the media end in the last segment when the"
            + " source holds packets after it")
    void shouldCountIntervalOfKeyframeAtMediaEndInLastSegmentWhenSourceHoldsPacketsAfterIt() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(12))
              .keyframeMillis(List.of(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 12_000L))
              .videoBytes(concat(repeat(6, 100_000L), repeat(1, 600_000L)))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.none()), "t");

      assertThat(playlist).contains("BANDWIDTH=1202176,AVERAGE-BANDWIDTH=801920,");
    }

    // ADR 0054 bounds a segment's audio by the keyframe intervals that overlap its interval
    // widened by one 32 ms AC-3 frame on each side. Segment 1 runs from 6 to 12 s, and the 20 ms
    // interval from 12 s is shorter than a frame, so segment 1's audio also spans the interval from
    // 12.02 s with its 400,000 bytes: intervals 0 to 4, 803,864 bytes with framing, 1,071,818.7
    // b/s. The video's busiest segment adds 7,509.3 b/s. Without the frame, segment 1 would end its
    // audio at 12.02 s and the peak would be segment 2's 406,024 bytes.
    @Test
    @DisplayName(
        "Should widen a segment's audio by one audio frame when a neighbouring keyframe interval is"
            + " shorter than a frame")
    void shouldWidenSegmentsAudioByOneAudioFrameWhenNeighbouringIntervalIsShorterThanAFrame() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(18))
              .keyframeMillis(List.of(0L, 6_000L, 11_980L, 12_000L, 12_020L, 14_000L, 16_000L))
              .videoBytes(repeat(7, 1_000L))
              .audioBytes(List.of(400_000L, 1_000L, 1_000L, 1_000L, 400_000L, 1_000L, 1_000L))
              .audioPackets(repeat(7, 10L))
              .build();

      var playlist = service.generateMultivariantPlaylist(copySession(source, AC3_COPY), "t");

      assertThat(playlist).contains("BANDWIDTH=1079329,AVERAGE-BANDWIDTH=362795,");
    }

    // ADR 0037: the worker puts an audio sample in the last segment that starts at or before the
    // sample's start, so segment 1, which opens at 8 s, late in slot 1, holds the audio up to
    // segment 2's start at 14 s. Widened by one 32 ms frame on each side, that span overlaps all
    // three intervals: 1,440,000 bytes, 9,008 bytes for 563 frames and 64, over 6 s, 1,932,096
    // b/s, the audio peak. The video's last segment adds 2,960 b/s.
    @Test
    @DisplayName(
        "Should bound a segment's copied audio by its exact start and the next segment's when its"
            + " keyframe opens late in its slot")
    void shouldBoundSegmentsCopiedAudioByTheExactStartsWhenItsKeyframeOpensLateInItsSlot() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(18))
              .keyframeMillis(List.of(0L, 8_000L, 14_000L))
              .videoBytes(repeat(3, 1_000L))
              .audioBytes(List.of(640_000L, 480_000L, 320_000L))
              .audioPackets(List.of(250L, 188L, 125L))
              .build();

      var playlist = service.generateMultivariantPlaylist(copySession(source, AC3_COPY), "t");

      assertThat(playlist).contains("BANDWIDTH=1935056,AVERAGE-BANDWIDTH=646063,");
    }

    // ADR 0054 charges a copied audio track's 64 bytes in every segment, even one without an audio
    // sample: 85.3 b/s over 6 s beside segment 0's 901,248 video bytes, 1,201,664 b/s.
    @Test
    @DisplayName(
        "Should charge the copied audio track's framing in every segment when the track holds no"
            + " sample")
    void shouldChargeCopiedAudioTracksFramingInEverySegmentWhenTheTrackHoldsNoSample() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(12))
              .keyframeMillis(List.of(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L))
              .videoBytes(concat(repeat(3, 300_000L), repeat(3, 100_000L)))
              .audioBytes(repeat(6, 0L))
              .audioPackets(repeat(6, 0L))
              .build();

      var playlist = service.generateMultivariantPlaylist(copySession(source, AC3_COPY), "t");

      assertThat(playlist).contains("BANDWIDTH=1201750,AVERAGE-BANDWIDTH=801750,");
    }

    // ADR 0054: an encoded audio rendition's peak is 1.2 × its bitrate plus its framing. The AAC
    // encoder runs at its highest rate, 96 kHz, since the source names none. A 6 s segment holds
    // 6 × 96,000 / 1,024 frames rounded up, plus 1 for a frame that crosses an edge; segment 0
    // also holds the 1,024 priming samples: 565 frames of 1,024 samples. Each frame carries
    // 1.2 × 128,000 / 8 bytes a second over its 10.67 ms, 204.8 bytes, and 16 bytes of framing,
    // and the segment 64: 124,816 bytes, 166,421.3 b/s. The video's segment 2 adds 1,203,200 b/s.
    @Test
    @DisplayName(
        "Should add 1.2 times the encoded audio's bitrate plus its framing when the session encodes"
            + " the audio")
    void shouldAddOnePointTwoTimesEncodedAudiosBitratePlusItsFramingWhenSessionEncodesAudio() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(everySecond(36))
              .videoBytes(concat(repeat(12, 50_000L), repeat(6, 150_000L), repeat(18, 50_000L)))
              .audioBytes(repeat(36, 80_000L))
              .audioPackets(repeat(36, 32L))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.stereoAac()), "t");

      assertThat(playlist).contains("BANDWIDTH=1369622,AVERAGE-BANDWIDTH=676623,");
    }

    // ADR 0037: encoded audio, like copied audio, fills each segment from its exact start to the
    // next one's. Segments open at 0, 8 and 14 s, so the last segment carries its own 4 s of audio:
    // 376 frames of 204.8 bytes and 16 bytes of framing each, and 64 bytes, 83,084.8 bytes over
    // 4 s, 166,169.6 b/s, the audio peak. The video's last segment adds 2,960 b/s.
    @Test
    @DisplayName(
        "Should size each segment's encoded audio by its exact start and the next segment's when"
            + " keyframes open segments late in their slots")
    void shouldSizeEachSegmentsEncodedAudioByTheExactStartsWhenKeyframesOpenSegmentsLate() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(18))
              .keyframeMillis(List.of(0L, 8_000L, 14_000L))
              .videoBytes(repeat(3, 1_000L))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.stereoAac()), "t");

      assertThat(playlist).contains("BANDWIDTH=169130,AVERAGE-BANDWIDTH=142070,");
    }

    // RFC 8216 §4.1 counts no run that holds the 2.6 s segment of 10,000,000 bytes: alone it lasts
    // less than 3 s, and with either 6.45 s neighbour more than 9 s. The runs then peak at the
    // last segment's 2,213.1 b/s, far below the copy's average of 1,213,973.3 b/s.
    @Test
    @DisplayName(
        "Should advertise at least the average when the densest segment belongs to no run of the"
            + " peak")
    void shouldAdvertiseAtLeastTheAverageWhenTheDensestSegmentBelongsToNoRunOfThePeak() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(66))
              .keyframeMillis(
                  List.of(
                      0L, 6_450L, 12_900L, 19_350L, 25_800L, 32_250L, 38_700L, 45_150L, 51_600L,
                      54_200L, 60_650L))
              .videoBytes(concat(repeat(8, 1_000L), repeat(1, 10_000_000L), repeat(2, 1_000L)))
              .build();

      var playlist =
          service.generateMultivariantPlaylist(copySession(source, AudioDecision.none()), "t");

      assertThat(playlist).contains("BANDWIDTH=1213974,AVERAGE-BANDWIDTH=1213974,");
    }

    // ADR 0044 keeps BANDWIDTH at 1.2 × AVERAGE-BANDWIDTH for a copy without a peak. Video in 35
    // keyframe intervals does not follow the source's 36 keyframes and gives no peak.
    @Test
    @DisplayName(
        "Should keep BANDWIDTH at 1.2 times the average when the measured video's intervals do not"
            + " follow the keyframes")
    void shouldKeepBandwidthAtOnePointTwoTimesAverageWhenVideoIntervalsDoNotFollowKeyframes() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(everySecond(36))
              .videoBytes(repeat(35, 50_000L))
              .audioBytes(repeat(36, 80_000L))
              .audioPackets(repeat(36, 32L))
              .build();

      var playlist = service.generateMultivariantPlaylist(copySession(source, AC3_COPY), "t");

      assertThat(playlist).contains("BANDWIDTH=1243422,AVERAGE-BANDWIDTH=1036185,");
    }

    // ADR 0044 keeps BANDWIDTH at 1.2 × AVERAGE-BANDWIDTH for a copy without a peak. Copied audio
    // in 35 keyframe intervals does not follow the source's 36 keyframes and gives no peak.
    @Test
    @DisplayName(
        "Should keep BANDWIDTH at 1.2 times the average when the measured audio's intervals do not"
            + " follow the keyframes")
    void shouldKeepBandwidthAtOnePointTwoTimesAverageWhenAudioIntervalsDoNotFollowKeyframes() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(everySecond(36))
              .videoBytes(repeat(36, 50_000L))
              .audioBytes(repeat(35, 80_000L))
              .audioPackets(repeat(35, 32L))
              .build();

      var playlist = service.generateMultivariantPlaylist(copySession(source, AC3_COPY), "t");

      assertThat(playlist).contains("BANDWIDTH=1235388,AVERAGE-BANDWIDTH=1029490,");
    }

    // ADR 0054 measures only copies. An encoded 5 Mb/s variant with the session's 128 kb/s AAC
    // keeps
    // ADR 0044's 1.2 × its average, whatever the source's measurement holds.
    @Test
    @DisplayName(
        "Should keep encoded variants at 1.2 times their average when the source is measured")
    void shouldKeepEncodedVariantsAtOnePointTwoTimesTheirAverageWhenTheSourceIsMeasured() {
      var source =
          StreamMeasurementFixture.CopySource.builder()
              .duration(Duration.ofSeconds(36))
              .keyframeMillis(everySecond(36))
              .videoBytes(concat(repeat(12, 50_000L), repeat(6, 150_000L), repeat(18, 50_000L)))
              .build();
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .mediaProbe(source.probe())
              .variants(
                  List.of(
                      StreamSessionFixture.defaultVariantBuilder()
                          .width(1920)
                          .height(1080)
                          .videoBitrate(5_000_000L)
                          .label("1080p")
                          .build()))
              .transcodeDecision(encodedDecision("h264", new Fraction(24, 1)))
              .build();

      var playlist = service.generateMultivariantPlaylist(session, "t");

      assertThat(playlist).contains("BANDWIDTH=6153600,AVERAGE-BANDWIDTH=5128000,");
    }

    private StreamSession copySession(
        StreamMeasurementFixture.CopySource source, AudioDecision audio) {
      var probe = source.probe();
      return StreamSessionFixture.defaultSessionBuilder()
          .mediaProbe(probe)
          .transcodeDecision(
              StreamSessionFixture.remuxDecisionBuilder()
                  .videoSource(probe.videoPlaybackFacts())
                  .audioDecision(audio)
                  .build())
          .build();
    }
  }

  // RFC 8216bis section 4.4.2.2: a tag that the multivariant playlist carries should not appear in
  // the media playlists that it lists, and PRECISE=YES starts at the offset inside its segment.
  @Nested
  @DisplayName("EXT-X-START")
  class StartTag {

    @ParameterizedTest
    @CsvSource({"0, 0", "250000000, 0.25", "5381000000000, 5381", "1000000001, 1.000000001"})
    @DisplayName(
        "Should mark the start position before every variant when the stream session starts at a"
            + " position")
    void shouldMarkTheStartPositionBeforeEveryVariantWhenTheStreamSessionStartsAtAPosition(
        long startNanos, String timeOffset) {
      var session =
          StreamSessionFixture.abrSessionBuilder()
              .transcodeDecision(encodedDecision("h264", new Fraction(24, 1)))
              .startPosition(Duration.ofNanos(startNanos))
              .build();

      var lines = service.generateMultivariantPlaylist(session, "t").lines().toList();

      var startTag = "#EXT-X-START:TIME-OFFSET=" + timeOffset + ",PRECISE=YES";
      assertThat(lines).containsOnlyOnce(startTag);
      assertThat(lines.indexOf(startTag))
          .isLessThan(firstIndexStartingWith(lines, "#EXT-X-STREAM-INF:"));
    }

    @Test
    @DisplayName("Should mark the start position when a copy starts at a position")
    void shouldMarkTheStartPositionWhenACopyStartsAtAPosition() {
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .startPosition(Duration.ofSeconds(90))
              .build();

      assertThat(service.generateMultivariantPlaylist(session, "t"))
          .containsOnlyOnce("#EXT-X-START:TIME-OFFSET=90,PRECISE=YES\n");
    }

    @Test
    @DisplayName(
        "Should leave the start out of the media playlist when the session starts at a position")
    void shouldLeaveTheStartOutOfTheMediaPlaylistWhenTheSessionStartsAtAPosition() {
      var session =
          StreamSessionFixture.defaultSessionBuilder()
              .startPosition(Duration.ofSeconds(90))
              .build();

      assertThat(service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "t"))
          .doesNotContain("#EXT-X-START");
    }

    @Test
    @DisplayName("Should mark no start when the stream session has no start position")
    void shouldMarkNoStartWhenTheStreamSessionHasNoStartPosition() {
      var session = StreamSessionFixture.defaultSessionBuilder().build();

      assertThat(service.generateMultivariantPlaylist(session, "t")).doesNotContain("#EXT-X-START");
    }

    private static int firstIndexStartingWith(List<String> lines, String prefix) {
      return lines.indexOf(
          lines.stream().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow());
    }
  }
}
