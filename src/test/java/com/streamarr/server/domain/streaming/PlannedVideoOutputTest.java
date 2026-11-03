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

  @Test
  @DisplayName(
      "Should keep a copy's bitstream rewrites when the caller changes its list afterwards")
  void shouldKeepACopysBitstreamRewritesWhenTheCallerChangesItsListAfterwards() {
    var rewrites = new ArrayList<>(List.of(BitstreamRewrite.STRIP_HDR10_PLUS));
    var copy =
        PlannedVideoOutput.StreamCopy.builder()
            .dynamicRange(VideoDynamicRange.SDR)
            .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
            .codecLevel(OptionalInt.of(150))
            .bitstreamRewrites(rewrites)
            .build();

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
    var profile = Optional.of(VideoCodecProfile.H264_HIGH);
    var level = OptionalInt.of(40);
    List<BitstreamRewrite> noRewrites = List.of();
    var encodeProfile = Optional.of(EncodeCodecProfile.H264_HIGH);
    return Stream.of(
        Arguments.of(
            "dynamicRange",
            (ThrowingCallable)
                () -> new PlannedVideoOutput.StreamCopy(null, profile, level, noRewrites)),
        Arguments.of(
            "codecProfile",
            (ThrowingCallable)
                () -> new PlannedVideoOutput.StreamCopy(range, null, level, noRewrites)),
        Arguments.of(
            "codecLevel",
            (ThrowingCallable)
                () -> new PlannedVideoOutput.StreamCopy(range, profile, null, noRewrites)),
        Arguments.of(
            "bitstreamRewrites",
            (ThrowingCallable)
                () -> new PlannedVideoOutput.StreamCopy(range, profile, level, null)),
        Arguments.of("source", (ThrowingCallable) () -> PlannedVideoOutput.StreamCopy.of(null)),
        Arguments.of(
            "codecProfile", (ThrowingCallable) () -> new PlannedVideoOutput.Encode(null, range)),
        Arguments.of(
            "dynamicRange",
            (ThrowingCallable) () -> new PlannedVideoOutput.Encode(encodeProfile, null)),
        Arguments.of("codecProfile", (ThrowingCallable) () -> PlannedVideoOutput.Encode.sdr(null)));
  }
}
