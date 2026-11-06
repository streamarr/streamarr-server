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
import org.junit.jupiter.params.provider.ValueSource;

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
            () ->
                encodeBuilder()
                    .codecProfile(codecProfile)
                    .dynamicRange(VideoDynamicRange.UNSPECIFIED)
                    .build())
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

  // ADR 0013: a worker that predates the planned output passes the source through unchanged, so
  // only an SDR copy without rewrites reaches it.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesAndWhetherTheyRequirePlannedOutputSupport")
  @DisplayName(
      "Should require planned output support when the copy's output is not SDR or it lists a"
          + " rewrite")
  void shouldRequirePlannedOutputSupportWhenTheCopysOutputIsNotSdrOrItListsARewrite(
      PlannedVideoOutput.StreamCopy copy, boolean required) {
    assertThat(copy.requiresPlannedOutputSupport()).isEqualTo(required);
  }

  static Stream<Arguments> copiesAndWhetherTheyRequirePlannedOutputSupport() {
    return Stream.of(
        Arguments.of(Named.of("an SDR copy without rewrites", copyBuilder().build()), false),
        Arguments.of(
            Named.of(
                "an SDR copy that keeps Dolby Vision profile 8.2",
                copyBuilder()
                    .dynamicRange(
                        new VideoDynamicRange(
                            CompatibleBase.SDR,
                            Presence.ABSENT,
                            new DolbyVision.Present(Optional.empty(), Presence.PRESENT)))
                    .build()),
            false),
        Arguments.of(Named.of("a PQ copy", copyOf(CompatibleBase.PQ)), true),
        Arguments.of(Named.of("an HLG copy", copyOf(CompatibleBase.HLG)), true),
        Arguments.of(
            Named.of(
                "a copy without a compatible base",
                copyBuilder()
                    .dynamicRange(
                        new VideoDynamicRange(
                            CompatibleBase.NONE,
                            Presence.ABSENT,
                            new DolbyVision.Present(Optional.empty(), Presence.PRESENT)))
                    .build()),
            true),
        Arguments.of(
            Named.of(
                "an SDR copy that lists a rewrite",
                copyBuilder()
                    .bitstreamRewrites(List.of(BitstreamRewrite.STRIP_HDR10_PLUS))
                    .build()),
            true));
  }

  private static PlannedVideoOutput.StreamCopy copyOf(CompatibleBase base) {
    return copyBuilder()
        .dynamicRange(new VideoDynamicRange(base, Presence.ABSENT, new DolbyVision.Absent()))
        .build();
  }

  // ADR 0013: only a copy that keeps Dolby Vision is described as Dolby Vision.
  @Test
  @DisplayName("Should refuse a Dolby Vision output when the planned copy carries no Dolby Vision")
  void shouldRefuseADolbyVisionOutputWhenThePlannedCopyCarriesNoDolbyVision() {
    var copy = copyOf(CompatibleBase.PQ);
    var output = new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6);

    assertThatThrownBy(() -> copy.withDolbyVisionOutput(output))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dolby Vision output");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 14})
  @DisplayName("Should refuse a Dolby Vision output when its level is outside the level table")
  void shouldRefuseADolbyVisionOutputWhenItsLevelIsOutsideTheLevelTable(int level) {
    assertThatThrownBy(() -> new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, level))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("level");
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
            "a stream copy of no source",
            "source",
            () -> PlannedVideoOutput.StreamCopy.of(null, List.of())),
        planWithoutValue(
            "an encode without a codec profile",
            "codecProfile",
            () -> encodeBuilder().codecProfile(null).dynamicRange(range).build()),
        planWithoutValue(
            "an encode without a dynamic range",
            "dynamicRange",
            () -> encodeBuilder().codecProfile(encodeProfile).dynamicRange(null).build()),
        planWithoutValue(
            "an encode without a chroma subsampling",
            "chromaSubsampling",
            () -> encodeBuilder().chromaSubsampling(null).build()),
        planWithoutValue(
            "a stream copy without a bit depth",
            "bitDepth",
            () -> copyBuilder().bitDepth(null).build()),
        planWithoutValue(
            "a stream copy without a chroma subsampling",
            "chromaSubsampling",
            () -> copyBuilder().chromaSubsampling(null).build()),
        planWithoutValue(
            "an SDR encode without a codec profile",
            "codecProfile",
            () -> PlannedVideoOutput.Encode.sdr(null)));
  }

  private static Arguments planWithoutValue(
      String plan, String missingValue, ThrowingCallable build) {
    return Arguments.of(Named.of(plan, missingValue), build);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 9, 12})
  @DisplayName("Should refuse a planned encode when its bit depth is neither 8 nor 10")
  void shouldRefuseAPlannedEncodeWhenItsBitDepthIsNeither8Nor10(int bitDepth) {
    var encode = encodeBuilder().bitDepth(bitDepth);

    assertThatThrownBy(encode::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("8 or 10");
  }

  // H.264 output stays 8-bit SDR in every case (#397): almost no device decodes 10-bit H.264.
  @ParameterizedTest(name = "{0}")
  @MethodSource("h264EncodesBeyond8BitSdr")
  @DisplayName("Should refuse a planned H.264 encode when it is not 8-bit SDR")
  void shouldRefuseAPlannedH264EncodeWhenItIsNot8BitSdr(
      String scenario, PlannedVideoOutput.Encode.EncodeBuilder encode) {
    assertThatThrownBy(encode::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("H.264");
  }

  static Stream<Arguments> h264EncodesBeyond8BitSdr() {
    return Stream.of(
        Arguments.of("a 10-bit SDR H.264 encode", encodeBuilder().bitDepth(10)),
        Arguments.of(
            "a 10-bit PQ H.264 encode",
            encodeBuilder()
                .bitDepth(10)
                .dynamicRange(
                    new VideoDynamicRange(
                        CompatibleBase.PQ, Presence.ABSENT, new DolbyVision.Absent()))));
  }

  private static PlannedVideoOutput.Encode.EncodeBuilder encodeBuilder() {
    return PlannedVideoOutput.Encode.builder()
        .codecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
        .dynamicRange(VideoDynamicRange.SDR)
        .bitDepth(8)
        .chromaSubsampling(ChromaSubsampling.CHROMA_420);
  }

  private static PlannedVideoOutput.StreamCopy.StreamCopyBuilder copyBuilder() {
    return PlannedVideoOutput.StreamCopy.builder()
        .dynamicRange(VideoDynamicRange.SDR)
        .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
        .codecLevel(OptionalInt.of(40))
        .bitstreamRewrites(List.of());
  }
}
