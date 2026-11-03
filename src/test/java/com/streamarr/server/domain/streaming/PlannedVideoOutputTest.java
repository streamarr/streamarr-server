package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Planned Video Output Tests")
class PlannedVideoOutputTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("rangesWithAnUnspecifiedValue")
  @DisplayName(
      "Should refuse a planned stream copy when its dynamic range holds an unspecified value")
  void shouldRefuseAPlannedStreamCopyWhenItsDynamicRangeHoldsAnUnspecifiedValue(
      VideoDynamicRange range) {
    var copy =
        PlannedVideoOutput.StreamCopy.builder()
            .dynamicRange(range)
            .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
            .codecLevel(OptionalInt.of(40))
            .bitstreamRewrites(List.of());

    assertThatThrownBy(copy::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNSPECIFIED");
  }

  static Stream<Named<VideoDynamicRange>> rangesWithAnUnspecifiedValue() {
    return Stream.of(
        Named.of(
            "an unspecified compatible base",
            new VideoDynamicRange(
                CompatibleBase.UNSPECIFIED, Presence.ABSENT, new DolbyVision.Absent())),
        Named.of(
            "an unspecified HDR10+ presence",
            new VideoDynamicRange(
                CompatibleBase.SDR, Presence.UNSPECIFIED, new DolbyVision.Absent())),
        Named.of(
            "unspecified Dolby Vision",
            new VideoDynamicRange(
                CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Unspecified())));
  }

  @Test
  @DisplayName("Should refuse a planned encode when its dynamic range is unspecified")
  void shouldRefuseAPlannedEncodeWhenItsDynamicRangeIsUnspecified() {
    var codecProfile = Optional.of(EncodeCodecProfile.H264_HIGH);

    assertThatThrownBy(
            () -> new PlannedVideoOutput.Encode(codecProfile, VideoDynamicRange.UNSPECIFIED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNSPECIFIED");
  }
}
