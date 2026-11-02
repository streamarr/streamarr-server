package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Viewer's Limits Tests")
class ViewerLimitsTest {

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceBitratesAgainstMaximumBitrates")
  @DisplayName(
      "Should allow a copy only when the source's known container bitrate is within the viewer's"
          + " maximum bitrate")
  void shouldAllowACopyOnlyWhenTheSourcesKnownContainerBitrateIsWithinTheViewersMaximumBitrate(
      CopyAgainstLimits copy) {
    assertThat(allowsCopy(copy)).isEqualTo(copy.allowed());
  }

  // A probe that names no container bitrate reports 0, and a maximum at or below zero is none.
  static Stream<CopyAgainstLimits> sourceBitratesAgainstMaximumBitrates() {
    var fullHd = new PictureSize(1920, 1080);
    var fourMegabits = auto().maxBitrate(4_000_000).build();
    return Stream.of(
        copyOf(fullHd).sourceBitrate(4_000_000L).options(fourMegabits).allowed(true).build(),
        copyOf(fullHd).sourceBitrate(4_000_001L).options(fourMegabits).allowed(false).build(),
        copyOf(fullHd).sourceBitrate(0L).options(fourMegabits).allowed(true).build(),
        copyOf(fullHd)
            .sourceBitrate(40_000_000L)
            .options(auto().maxBitrate(0).build())
            .allowed(true)
            .build(),
        copyOf(fullHd).sourceBitrate(40_000_000L).options(auto().build()).allowed(true).build());
  }

  @Test
  @DisplayName("Should allow every copy when the viewer gives no quality")
  void shouldAllowEveryCopyWhenTheViewerGivesNoQuality() {
    var limits = ViewerLimits.of(StreamingOptions.builder().build());

    assertThat(limits.allowsCopyOf(defaultProbeBuilder().width(7680).height(4320).build()))
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

    assertThat(limits.allowsCopyOf(defaultProbeBuilder().bitrate(40_000_000L).build())).isTrue();
  }

  private static boolean allowsCopy(CopyAgainstLimits copy) {
    var source =
        defaultProbeBuilder()
            .width(copy.source().width())
            .height(copy.source().height())
            .bitrate(copy.sourceBitrate())
            .build();
    return ViewerLimits.of(copy.options()).allowsCopyOf(source);
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
