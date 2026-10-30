package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Viewer's Limits Tests")
class ViewerLimitsTest {

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

    assertThat(limits.allowsCopyOf(defaultProbeBuilder().width(width).height(height).build()))
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

    assertThat(limits.allowsCopyOf(defaultProbeBuilder().width(width).height(height).build()))
        .isEqualTo(allowed);
  }

  @ParameterizedTest
  @CsvSource({
    "4000000, 4000000, true",
    "4000001, 4000000, false",
    "0, 4000000, true",
    "40000000, 0, true",
    "40000000, , true"
  })
  @DisplayName(
      "Should allow a copy only when the source's known container bitrate is within the viewer's"
          + " maximum bitrate")
  void shouldAllowACopyOnlyWhenTheSourcesKnownContainerBitrateIsWithinTheViewersMaximumBitrate(
      long sourceBitrate, Integer maxBitrate, boolean allowed) {
    var limits =
        ViewerLimits.of(
            StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build());

    assertThat(limits.allowsCopyOf(defaultProbeBuilder().bitrate(sourceBitrate).build()))
        .isEqualTo(allowed);
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
}
