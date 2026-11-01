package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Viewer's Limits Tests")
class ViewerLimitsTest {

  private static final AudioSteps COPIED_AAC_ONLY =
      new AudioSteps(
          List.of(
              AudioDecision.stereoAac().toBuilder()
                  .mode(AudioMode.COPY)
                  .sourceCodecString(Optional.of("mp4a.40.2"))
                  .build()));

  private static final AudioSteps STEREO_AAC_ONLY =
      new AudioSteps(List.of(AudioDecision.stereoAac()));

  // The 1,200 segments of 6 s that a stream copy of the default 2-hour source lists.
  private static final CopyMediaPlaylist TWO_HOURS =
      CopyMediaPlaylistFixture.evenlyListing(1_200, "6.000000");

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesAgainstFixedQualityBoxes")
  @DisplayName(
      "Should allow a copy only when the source fits the fixed quality's picture box in either"
          + " orientation")
  void shouldAllowACopyOnlyWhenTheSourceFitsTheFixedQualitysPictureBoxInEitherOrientation(
      CopyAgainstLimits copy) {
    assertThat(allowsCopy(copy)).isEqualTo(copy.allowed());
  }

  // ADR 0050's boxes: 1280×720 for 720p, 1920×1080 for 1080p and 3840×2160 for 4K. Auto has none.
  static Stream<CopyAgainstLimits> sourcesAgainstFixedQualityBoxes() {
    var fullHd = new PictureSize(1920, 1080);
    var portraitFullHd = new PictureSize(1080, 1920);
    var uhd = new PictureSize(3840, 2160);
    return Stream.of(
        copyOf(fullHd).options(quality(VideoQuality.FULL_HD_1080P)).allowed(true).build(),
        copyOf(portraitFullHd).options(quality(VideoQuality.FULL_HD_1080P)).allowed(true).build(),
        copyOf(fullHd).options(quality(VideoQuality.HIGH_720P)).allowed(false).build(),
        copyOf(new PictureSize(720, 1280))
            .options(quality(VideoQuality.HIGH_720P))
            .allowed(true)
            .build(),
        copyOf(portraitFullHd).options(quality(VideoQuality.HIGH_720P)).allowed(false).build(),
        copyOf(uhd).options(quality(VideoQuality.UHD_4K)).allowed(true).build(),
        copyOf(uhd).options(quality(VideoQuality.AUTO)).allowed(true).build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesAgainstMaximumWidthsAndHeights")
  @DisplayName(
      "Should allow a copy only when the source fits the viewer's maximum width and height as"
          + " the client gives them")
  void shouldAllowACopyOnlyWhenTheSourceFitsTheViewersMaximumWidthAndHeightAsTheClientGivesThem(
      CopyAgainstLimits copy) {
    assertThat(allowsCopy(copy)).isEqualTo(copy.allowed());
  }

  // The maximums are not turned for a portrait picture, and a maximum at or below zero is none.
  static Stream<CopyAgainstLimits> sourcesAgainstMaximumWidthsAndHeights() {
    var fullHd = new PictureSize(1920, 1080);
    var portraitFullHd = new PictureSize(1080, 1920);
    return Stream.of(
        copyOf(fullHd).options(auto().maxWidth(1920).maxHeight(1080).build()).allowed(true).build(),
        copyOf(fullHd).options(auto().maxWidth(1919).build()).allowed(false).build(),
        copyOf(fullHd).options(auto().maxHeight(1079).build()).allowed(false).build(),
        copyOf(portraitFullHd)
            .options(auto().maxWidth(1920).maxHeight(1080).build())
            .allowed(false)
            .build(),
        copyOf(portraitFullHd)
            .options(auto().maxWidth(1080).maxHeight(1920).build())
            .allowed(true)
            .build(),
        copyOf(fullHd).options(auto().maxWidth(0).maxHeight(0).build()).allowed(true).build());
  }

  // The 4 Mb/s container bitrate stands in for the video of a probe that lists no streams and holds
  // the copied audio track, so the copy costs 4 Mb/s.
  @Test
  @DisplayName(
      "Should keep the session's audio beside a copy when the copy's cost equals the maximum"
          + " bitrate")
  void shouldKeepTheSessionsAudioBesideACopyWhenTheCopysCostEqualsTheMaximumBitrate() {
    var limits = ViewerLimits.of(auto().maxBitrate(4_000_000).build());

    var audio =
        limits.audioStepBesideCopyOf(
            defaultProbeBuilder().bitrate(4_000_000L).build(),
            COPIED_AAC_ONLY,
            CopyMediaPlaylist.UNKNOWN);

    assertThat(audio).contains(COPIED_AAC_ONLY.sessionAudio());
  }

  @Test
  @DisplayName(
      "Should find no audio step when a copy exceeds the maximum bitrate beside every audio step")
  void shouldFindNoAudioStepWhenACopyExceedsTheMaximumBitrateBesideEveryAudioStep() {
    var limits = ViewerLimits.of(auto().maxBitrate(4_000_000).build());

    var audio =
        limits.audioStepBesideCopyOf(
            defaultProbeBuilder().bitrate(4_000_001L).build(),
            COPIED_AAC_ONLY,
            CopyMediaPlaylist.UNKNOWN);

    assertThat(audio).isEmpty();
  }

  // A maximum at or below zero is none.
  @ParameterizedTest(name = "{0}")
  @MethodSource("limitsWithoutAMaximumBitrate")
  @DisplayName(
      "Should keep the session's audio beside a copy when the viewer sets no maximum bitrate")
  void shouldKeepTheSessionsAudioBesideACopyWhenTheViewerSetsNoMaximumBitrate(
      StreamingOptions options) {
    var audio =
        ViewerLimits.of(options)
            .audioStepBesideCopyOf(
                defaultProbeBuilder().bitrate(40_000_000L).build(),
                COPIED_AAC_ONLY,
                CopyMediaPlaylist.UNKNOWN);

    assertThat(audio).contains(COPIED_AAC_ONLY.sessionAudio());
  }

  static Stream<StreamingOptions> limitsWithoutAMaximumBitrate() {
    return Stream.of(auto().build(), auto().maxBitrate(0).build());
  }

  // A copy of 4.5 Mb/s video costs 5,268 kb/s beside 768 kb/s E-AC-3 5.1 and 4,948 kb/s beside
  // 448 kb/s.
  @ParameterizedTest(name = "{0}")
  @MethodSource("maximumsBelowTheCopyBesideTheSessionsAudio")
  @DisplayName(
      "Should take the first audio step beside which a copy fits when the session's audio leaves"
          + " the copy over the maximum bitrate")
  void
      shouldTakeTheFirstAudioStepBesideWhichACopyFitsWhenTheSessionsAudioLeavesTheCopyOverTheMaximumBitrate(
          FirstStepBesideCopy expected) {
    var limits = ViewerLimits.of(auto().maxBitrate(expected.maxBitrate()).build());
    var probe = defaultProbeBuilder();
    var source =
        probe
            .streams(
                List.of(
                    ProbeFixture.videoStreamBuilder(probe.build())
                        .bitrate(OptionalLong.of(4_500_000L))
                        .build()))
            .build();
    var eac3 = AudioDecision.stereoAac().toBuilder().codec("eac3").channels(6);
    var steps =
        new AudioSteps(List.of(eac3.bitrate(768_000L).build(), eac3.bitrate(448_000L).build()));

    var audio = limits.audioStepBesideCopyOf(source, steps, CopyMediaPlaylist.UNKNOWN);

    assertThat(audio.map(AudioDecision::bitrate)).contains(expected.bitrate());
  }

  static Stream<FirstStepBesideCopy> maximumsBelowTheCopyBesideTheSessionsAudio() {
    return Stream.of(
        FirstStepBesideCopy.builder().maxBitrate(5_267_999).bitrate(448_000L).build(),
        FirstStepBesideCopy.builder().maxBitrate(4_948_000).bitrate(448_000L).build());
  }

  @Builder
  record FirstStepBesideCopy(int maxBitrate, long bitrate) {}

  // ADR 0054: over the 1,200 segments of the 2-hour default media playlist, 4,050,000,000 measured
  // video bytes, 115,216 bytes of video framing and the encoded AAC's 10,876,816 bytes of framing
  // for 675,001 frames at the encoder's highest rate of 96 kHz average 4,512,214 b/s, which the
  // viewer's maximum bitrate checks instead of the probed 6 Mb/s video and the 8 Mb/s container.
  // The session's AAC adds its 128 kb/s.
  @Test
  @DisplayName(
      "Should keep the session's audio beside a copy when the copy's measured average equals the"
          + " maximum bitrate")
  void shouldKeepTheSessionsAudioBesideACopyWhenTheCopysMeasuredAverageEqualsTheMaximumBitrate() {
    var limits = ViewerLimits.of(auto().maxBitrate(4_640_214).build());

    var audio = limits.audioStepBesideCopyOf(measuredSource(), STEREO_AAC_ONLY, TWO_HOURS);

    assertThat(audio).contains(AudioDecision.stereoAac());
  }

  @Test
  @DisplayName(
      "Should find no audio step when a copy's measured average exceeds the maximum bitrate by one"
          + " bit per second")
  void shouldFindNoAudioStepWhenACopysMeasuredAverageExceedsTheMaximumBitrateByOneBitPerSecond() {
    var limits = ViewerLimits.of(auto().maxBitrate(4_640_213).build());

    var audio = limits.audioStepBesideCopyOf(measuredSource(), STEREO_AAC_ONLY, TWO_HOURS);

    assertThat(audio).isEmpty();
  }

  // A source of 6 Mb/s probed video in an 8 Mb/s container, whose video the server measured at
  // 4,050,000,000 bytes over 2 hours.
  private static MediaProbe measuredSource() {
    var probe = defaultProbeBuilder().bitrate(8_000_000L);
    var video =
        ProbeFixture.videoStreamBuilder(probe.build()).bitrate(OptionalLong.of(6_000_000L)).build();
    var measurement =
        new StreamMeasurements(
            StreamMeasurementSource.SAMPLE_TABLE,
            List.of(
                StreamMeasurementFixture.measuredStreamBuilder(video.index(), 4_050_000_000L)
                    .measuredDuration(7_200_000)
                    .build()));
    return probe.streams(List.of(video)).streamMeasurements(Optional.of(measurement)).build();
  }

  // ADR 0054 checks a copy's measured average, not its peak, against the maximum bitrate. Segment
  // 0 of the 12 s copy holds 3,000,000 video bytes, a peak of 4,003,200 b/s, while the copy
  // averages 2,203,200 b/s.
  @Test
  @DisplayName(
      "Should keep the audio step beside a copy whose peak exceeds the maximum bitrate when the"
          + " copy's measured average equals it")
  void shouldKeepTheAudioStepBesideACopyWhosePeakExceedsTheMaximumBitrateWhenItsAverageEqualsIt() {
    var limits = ViewerLimits.of(auto().maxBitrate(2_203_200).build());

    var audio = audioStepBesideCopyOfDenseFirstSegment(limits);

    assertThat(audio).contains(AudioDecision.none());
  }

  @Test
  @DisplayName(
      "Should find no audio step beside a copy whose peak exceeds the maximum bitrate when the"
          + " copy's measured average exceeds it by one bit per second")
  void shouldFindNoAudioStepBesideACopyWhosePeakExceedsTheMaximumBitrateWhenItsAverageExceedsIt() {
    var limits = ViewerLimits.of(auto().maxBitrate(2_203_199).build());

    var audio = audioStepBesideCopyOfDenseFirstSegment(limits);

    assertThat(audio).isEmpty();
  }

  // A 12 s copy on 6 s segments whose first 6 keyframe intervals hold 500,000 video bytes each and
  // whose last 6 hold 50,000, beside no audio.
  private static Optional<AudioDecision> audioStepBesideCopyOfDenseFirstSegment(
      ViewerLimits limits) {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(12))
            .keyframeMillis(StreamMeasurementFixture.everySecond(12))
            .videoBytes(
                StreamMeasurementFixture.concat(
                    StreamMeasurementFixture.repeat(6, 500_000L),
                    StreamMeasurementFixture.repeat(6, 50_000L)))
            .build()
            .probe();
    var playlist =
        CopyMediaPlaylist.of(
            source,
            StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build());
    return limits.audioStepBesideCopyOf(
        source, new AudioSteps(List.of(AudioDecision.none())), playlist);
  }

  // A probe without playback facts names no copied stream, so the container bitrate stands in for
  // the video.
  @ParameterizedTest
  @CsvSource({"4000000, true", "3999999, false"})
  @DisplayName(
      "Should check a copy against the container bitrate when the probe names no copied video"
          + " stream")
  void shouldCheckACopyAgainstTheContainerBitrateWhenTheProbeNamesNoCopiedVideoStream(
      int maxBitrate, boolean allowed) {
    var limits =
        ViewerLimits.of(
            StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build());
    var source =
        defaultProbeBuilder().bitrate(4_000_000L).videoPlaybackFacts(Optional.empty()).build();

    assertThat(
            limits
                .audioStepBesideCopyOf(source, COPIED_AAC_ONLY, CopyMediaPlaylist.UNKNOWN)
                .isPresent())
        .isEqualTo(allowed);
  }

  @Test
  @DisplayName(
      "Should refuse to check a copy when the source, the audio steps or the playlist is missing")
  void shouldRefuseToCheckACopyWhenTheSourceTheAudioStepsOrThePlaylistIsMissing() {
    var limits = ViewerLimits.none();
    var source = defaultProbeBuilder().build();
    var playlist = CopyMediaPlaylist.UNKNOWN;

    assertThatNullPointerException()
        .isThrownBy(() -> limits.allowsCopyPictureOf(null))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> limits.audioStepBesideCopyOf(null, COPIED_AAC_ONLY, playlist))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> limits.audioStepBesideCopyOf(source, null, playlist))
        .withMessageContaining("steps");
    assertThatNullPointerException()
        .isThrownBy(() -> limits.audioStepBesideCopyOf(source, COPIED_AAC_ONLY, null))
        .withMessageContaining("playlist");
  }

  @Test
  @DisplayName("Should allow every copy when the viewer gives no quality")
  void shouldAllowEveryCopyWhenTheViewerGivesNoQuality() {
    var limits = ViewerLimits.of(StreamingOptions.builder().build());

    assertThat(limits.allowsCopyPictureOf(defaultProbeBuilder().width(7680).height(4320).build()))
        .isTrue();
  }

  @Test
  @DisplayName("Should allow a copy in Original when the source exceeds every one of the limits")
  void shouldAllowACopyInOriginalWhenTheSourceExceedsEveryOneOfTheLimits() {
    var limits =
        ViewerLimits.of(
            StreamingOptions.builder()
                .quality(VideoQuality.ORIGINAL)
                .maxWidth(640)
                .maxHeight(360)
                .maxBitrate(1_000_000)
                .build());

    var source = defaultProbeBuilder().width(3840).height(2160).bitrate(40_000_000L).build();

    assertThat(limits.allowsCopyPictureOf(source)).isTrue();
    assertThat(limits.audioStepBesideCopyOf(source, COPIED_AAC_ONLY, CopyMediaPlaylist.UNKNOWN))
        .contains(COPIED_AAC_ONLY.sessionAudio());
  }

  private static boolean allowsCopy(CopyAgainstLimits copy) {
    var source =
        defaultProbeBuilder().width(copy.source().width()).height(copy.source().height()).build();
    return ViewerLimits.of(copy.options()).allowsCopyPictureOf(source);
  }

  private static CopyAgainstLimits.CopyAgainstLimitsBuilder copyOf(PictureSize source) {
    return CopyAgainstLimits.builder().source(source);
  }

  private static StreamingOptions quality(VideoQuality quality) {
    return StreamingOptions.builder().quality(quality).build();
  }

  private static StreamingOptions.StreamingOptionsBuilder auto() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO);
  }

  @Builder
  record CopyAgainstLimits(PictureSize source, StreamingOptions options, boolean allowed) {}
}
