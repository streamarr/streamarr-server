package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fixtures.ProbeFixture;
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
  @DisplayName("Should refuse a stream copy playlist when the keyframes cannot open every segment")
  void shouldRefuseAStreamCopyPlaylistWhenTheKeyframesCannotOpenEverySegment(
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
            "a keyframe every 10.01 s",
            ProbeFixture.keyframesEvery(Duration.ofMillis(10_010), Duration.ofMinutes(120))));
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
  @DisplayName("Should include extra segment when duration has sub-second remainder")
  void shouldIncludeExtraSegmentWhenDurationHasSubSecondRemainder() {
    var session = createSessionWithDuration(TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(18500));

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var segmentLines =
        playlist
            .lines()
            .filter(l -> l.startsWith("segment") && l.endsWith(".m4s?t=test-token"))
            .toList();
    assertThat(segmentLines).hasSize(4);
  }

  @Test
  @DisplayName("Should calculate last segment duration when duration has sub-second precision")
  void shouldCalculateLastSegmentDurationWhenDurationHasSubSecondPrecision() {
    var session = createSessionWithDuration(TranscodeMode.FULL_TRANSCODE, Duration.ofMillis(18500));

    var playlist =
        service.generateMediaPlaylist(session, StreamSession.defaultVariant(), "test-token");

    var extinfLines = playlist.lines().filter(l -> l.startsWith("#EXTINF:")).toList();
    assertThat(extinfLines).hasSize(4);

    var lastExtinf = extinfLines.getLast();
    var duration = Double.parseDouble(lastExtinf.replace("#EXTINF:", "").replace(",", ""));
    assertThat(duration).isCloseTo(0.5, within(0.01));
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

    @Test
    @DisplayName(
        "Should leave the video range out when the session copies a source without Dolby Vision")
    void shouldLeaveTheVideoRangeOutWhenTheSessionCopiesASourceWithoutDolbyVision() {
      var session = copySession(VideoPlaybackFactsFixture.sdrH264FactsBuilder());

      var playlist = service.generateMultivariantPlaylist(session, "test-token");

      assertThat(playlist).doesNotContain("VIDEO-RANGE");
    }

    private StreamSession copySession(VideoPlaybackFacts.VideoPlaybackFactsBuilder source) {
      return StreamSessionFixture.defaultSessionBuilder()
          .transcodeDecision(
              TranscodeDecision.builder()
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
                  .videoSource(Optional.of(source.build()))
                  .build())
          .build();
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

    @ParameterizedTest(name = "{0} fps")
    @CsvSource({"24000, 1001, 23.976", "30000, 1001, 29.970", "60, 1, 60.000", "25, 1, 25.000"})
    @DisplayName(
        "Should advertise the output frame rate to three decimals when every variant encodes")
    void shouldAdvertiseTheOutputFrameRateToThreeDecimalsWhenEveryVariantEncodes(
        int numerator, int denominator, String frameRate) {
      var rate = new Fraction(numerator, denominator);
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

    @Test
    @DisplayName(
        "Should advertise no frame rate when the session copies a stream whose declared rates"
            + " agree")
    void shouldAdvertiseNoFrameRateWhenTheSessionCopiesAStreamWhoseDeclaredRatesAgree() {
      var session =
          copySessionWithRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(25, 1)));

      assertThat(streamInf(session)).singleElement().asString().doesNotContain("FRAME-RATE");
    }

    private StreamSession copySessionWithRule(FrameRateRule rule) {
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder().streamIndex(0).frameRateRule(rule);
      return StreamSessionFixture.defaultSessionBuilder()
          .mediaProbe(probeWithFacts(facts))
          .transcodeDecision(
              StreamSessionFixture.remuxDecisionBuilder()
                  .videoSource(Optional.of(facts.build()))
                  .build())
          .build();
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
}
