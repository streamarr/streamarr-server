package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fixtures.ProbeFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @ParameterizedTest
  @CsvSource({
    "1920, 1080, FULL_HD_1080P, true",
    "1080, 1920, FULL_HD_1080P, true",
    "1920, 1080, HIGH_720P, false",
    "720, 1280, HIGH_720P, true",
    "1080, 1920, HIGH_720P, false",
    "3840, 2160, UHD_4K, true",
    "3840, 2160, AUTO, true"
  })
  @DisplayName(
      "Should allow a copy only when the source fits the fixed quality's picture box in either"
          + " orientation")
  void shouldAllowACopyOnlyWhenTheSourceFitsTheFixedQualitysPictureBoxInEitherOrientation(
      int width, int height, VideoQuality quality, boolean allowed) {
    var limits = ViewerLimits.of(StreamingOptions.builder().quality(quality).build());

    assertThat(
            limits.allowsCopyPictureOf(defaultProbeBuilder().width(width).height(height).build()))
        .isEqualTo(allowed);
  }

  @ParameterizedTest
  @CsvSource({
    "1920, 1080, 1920, 1080, true",
    "1920, 1080, 1919, , false",
    "1920, 1080, , 1079, false",
    "1080, 1920, 1920, 1080, false",
    "1080, 1920, 1080, 1920, true",
    "1920, 1080, 0, 0, true"
  })
  @DisplayName(
      "Should allow a copy only when the source fits the viewer's maximum width and height as"
          + " the client gives them")
  void shouldAllowACopyOnlyWhenTheSourceFitsTheViewersMaximumWidthAndHeightAsTheClientGivesThem(
      int width, int height, Integer maxWidth, Integer maxHeight, boolean allowed) {
    var limits =
        ViewerLimits.of(
            StreamingOptions.builder()
                .quality(VideoQuality.AUTO)
                .maxWidth(maxWidth)
                .maxHeight(maxHeight)
                .build());

    assertThat(
            limits.allowsCopyPictureOf(defaultProbeBuilder().width(width).height(height).build()))
        .isEqualTo(allowed);
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
}
