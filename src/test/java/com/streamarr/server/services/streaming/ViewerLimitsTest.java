package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fixtures.ProbeFixture;
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

  // The source's container bitrate holds the copied audio track, so the copy costs that bitrate.
  @ParameterizedTest
  @CsvSource({
    "4000000, 4000000, true",
    "4000001, 4000000, false",
    "0, 4000000, true",
    "40000000, 0, true",
    "40000000, , true"
  })
  @DisplayName(
      "Should keep the copied audio beside a copy only when the source's known container bitrate is"
          + " within the viewer's maximum bitrate")
  void
      shouldKeepTheCopiedAudioBesideACopyOnlyWhenTheSourcesKnownContainerBitrateIsWithinTheViewersMaximumBitrate(
          long sourceBitrate, Integer maxBitrate, boolean allowed) {
    var limits =
        ViewerLimits.of(
            StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build());

    var audio =
        limits.audioStepBesideCopyOf(
            defaultProbeBuilder().bitrate(sourceBitrate).build(), COPIED_AAC_ONLY);

    assertThat(audio.isPresent()).isEqualTo(allowed);
    audio.ifPresent(step -> assertThat(step).isEqualTo(COPIED_AAC_ONLY.sessionAudio()));
  }

  // A copy of 4.5 Mb/s video costs 5,268 kb/s beside 768 kb/s E-AC-3 5.1 and 4,948 kb/s beside
  // 448 kb/s.
  @ParameterizedTest
  @CsvSource({"5268000, 768000", "5000000, 448000", "4500000, -1"})
  @DisplayName(
      "Should take the first audio step beside which a copy fits the maximum bitrate when the"
          + " session's audio does not")
  void shouldTakeTheFirstAudioStepBesideWhichACopyFitsTheMaximumBitrateWhenTheSessionsAudioDoesNot(
      int maxBitrate, long expectedBitrate) {
    var limits =
        ViewerLimits.of(
            StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build());
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

    var audio = limits.audioStepBesideCopyOf(source, steps);

    assertThat(audio.map(AudioDecision::bitrate).orElse(-1L)).isEqualTo(expectedBitrate);
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

    assertThat(limits.audioStepBesideCopyOf(source, COPIED_AAC_ONLY).isPresent())
        .isEqualTo(allowed);
  }

  @Test
  @DisplayName("Should refuse to check a copy when the source or the audio steps are missing")
  void shouldRefuseToCheckACopyWhenTheSourceOrTheAudioStepsAreMissing() {
    var limits = ViewerLimits.none();
    var source = defaultProbeBuilder().build();

    assertThatNullPointerException()
        .isThrownBy(() -> limits.allowsCopyPictureOf(null))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> limits.audioStepBesideCopyOf(null, COPIED_AAC_ONLY))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> limits.audioStepBesideCopyOf(source, null))
        .withMessageContaining("steps");
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
    assertThat(limits.audioStepBesideCopyOf(source, COPIED_AAC_ONLY))
        .contains(COPIED_AAC_ONLY.sessionAudio());
  }

  private static boolean allowsCopy(CopyAgainstLimits copy) {
    var source =
        defaultProbeBuilder()
            .width(copy.source().width())
            .height(copy.source().height())
            .bitrate(copy.sourceBitrate())
            .build();
    return ViewerLimits.of(copy.options()).allowsCopyPictureOf(source);
  }

  // A 5 Mb/s source of this size.
  private static CopyAgainstLimits.CopyAgainstLimitsBuilder copyOf(PictureSize source) {
    return CopyAgainstLimits.builder().source(source).sourceBitrate(5_000_000L);
  }

  private static StreamingOptions quality(VideoQuality quality) {
    return StreamingOptions.builder().quality(quality).build();
  }

  private static StreamingOptions.StreamingOptionsBuilder auto() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO);
  }

  @Builder
  record CopyAgainstLimits(
      PictureSize source, long sourceBitrate, StreamingOptions options, boolean allowed) {}
}
