package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
    var copy = copyBuilder().dynamicRange(range);

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

  @Test
  @DisplayName(
      "Should keep a copy's bitstream rewrites when the caller changes its list afterwards")
  void shouldKeepACopysBitstreamRewritesWhenTheCallerChangesItsListAfterwards() {
    var rewrites = new ArrayList<>(List.of(BitstreamRewrite.STRIP_HDR10_PLUS));
    var copy = copyBuilder().bitstreamRewrites(rewrites).build();

    rewrites.add(BitstreamRewrite.STRIP_DOLBY_VISION);

    assertThat(copy.bitstreamRewrites()).containsExactly(BitstreamRewrite.STRIP_HDR10_PLUS);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("plannedOutputsMissingARequiredValue")
  @DisplayName("Should refuse a planned output when a required value is missing")
  void shouldRefuseAPlannedOutputWhenARequiredValueIsMissing(
      String missingValue, ThrowingCallable plan) {
    assertThatThrownBy(plan)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(missingValue);
  }

  static Stream<Arguments> plannedOutputsMissingARequiredValue() {
    var range = VideoDynamicRange.SDR;
    var encodeProfile = Optional.of(EncodeCodecProfile.H264_HIGH);
    return Stream.of(
        planWithoutValue(
            "a stream copy without a dynamic range",
            "dynamicRange",
            () -> copyBuilder().dynamicRange(null).build()),
        planWithoutValue(
            "a stream copy without a codec profile",
            "codecProfile",
            () -> copyBuilder().codecProfile(null).build()),
        planWithoutValue(
            "a stream copy without a codec level",
            "codecLevel",
            () -> copyBuilder().codecLevel(null).build()),
        planWithoutValue(
            "a stream copy without bitstream rewrites",
            "bitstreamRewrites",
            () -> copyBuilder().bitstreamRewrites(null).build()),
        planWithoutValue(
            "a stream copy of no source", "source", () -> PlannedVideoOutput.StreamCopy.of(null)),
        planWithoutValue(
            "an encode without a codec profile",
            "codecProfile",
            () -> new PlannedVideoOutput.Encode(null, range)),
        planWithoutValue(
            "an encode without a dynamic range",
            "dynamicRange",
            () -> new PlannedVideoOutput.Encode(encodeProfile, null)),
        planWithoutValue(
            "an SDR encode without a codec profile",
            "codecProfile",
            () -> PlannedVideoOutput.Encode.sdr(null)));
  }

  private static Arguments planWithoutValue(
      String plan, String missingValue, ThrowingCallable build) {
    return Arguments.of(Named.of(plan, missingValue), build);
  }

  private static PlannedVideoOutput.StreamCopy.StreamCopyBuilder copyBuilder() {
    return PlannedVideoOutput.StreamCopy.builder()
        .dynamicRange(VideoDynamicRange.SDR)
        .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
        .codecLevel(OptionalInt.of(40))
        .bitstreamRewrites(List.of());
  }
}
