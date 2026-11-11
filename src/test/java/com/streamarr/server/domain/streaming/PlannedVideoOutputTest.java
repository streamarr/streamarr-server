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
import org.junit.jupiter.params.provider.EnumSource;
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
    var encode = encodeBuilder().dynamicRange(VideoDynamicRange.UNSPECIFIED);

    assertThatThrownBy(encode::build)
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

  // ADR 0050: the lower variants beside a copy keep its range and bit depth. An encode writes SDR
  // at 8 or 10 bits and PQ or HLG at 10 bits, and reshapes Dolby Vision without a compatible base
  // into PQ.
  @ParameterizedTest(name = "{0} at {1} bits")
  @MethodSource("copiesAndTheEncodeFormatInTheirRangeAndBitDepth")
  @DisplayName(
      "Should name the encode format in a copy's range and bit depth when an encode writes them")
  void shouldNameTheEncodeFormatInACopysRangeAndBitDepthWhenAnEncodeWritesThem(
      VideoDynamicRange range, int bitDepth, Optional<EncodeFormat> expected) {
    var copy = copyBuilder().dynamicRange(range).bitDepth(OptionalInt.of(bitDepth)).build();

    assertThat(copy.encodeFormatInItsRangeAndBitDepth()).isEqualTo(expected);
  }

  static Stream<Arguments> copiesAndTheEncodeFormatInTheirRangeAndBitDepth() {
    var sdr = Named.of("SDR", VideoDynamicRange.SDR);
    var pq = Named.of("PQ", copyOf(CompatibleBase.PQ).dynamicRange());
    var hlg = Named.of("HLG", copyOf(CompatibleBase.HLG).dynamicRange());
    var dolbyVisionWithoutABase =
        Named.of(
            "Dolby Vision without a compatible base",
            new VideoDynamicRange(
                CompatibleBase.NONE,
                Presence.ABSENT,
                new DolbyVision.Present(Optional.empty(), Presence.PRESENT)));
    var pqEncode = Optional.of(EncodeFormat.hdr(VideoRange.PQ));
    return Stream.of(
        Arguments.of(sdr, 8, Optional.of(EncodeFormat.SDR_8_BIT)),
        Arguments.of(sdr, 10, Optional.of(EncodeFormat.SDR_10_BIT)),
        Arguments.of(sdr, 12, Optional.empty()),
        Arguments.of(pq, 10, pqEncode),
        Arguments.of(pq, 8, Optional.empty()),
        Arguments.of(pq, 12, Optional.empty()),
        Arguments.of(hlg, 10, Optional.of(EncodeFormat.hdr(VideoRange.HLG))),
        Arguments.of(hlg, 12, Optional.empty()),
        Arguments.of(dolbyVisionWithoutABase, 10, pqEncode),
        Arguments.of(dolbyVisionWithoutABase, 12, Optional.empty()));
  }

  @Test
  @DisplayName(
      "Should name no encode format in a copy's range and bit depth when its bit depth is unknown")
  void shouldNameNoEncodeFormatInACopysRangeAndBitDepthWhenItsBitDepthIsUnknown() {
    var copy = copyBuilder().build();

    assertThat(copy.encodeFormatInItsRangeAndBitDepth()).isEmpty();
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

  // An encode left without a value reaches its constructor's check. The copy's builder defaults its
  // bit depth and chroma subsampling, so only the canonical constructor can receive null for them.
  static Stream<Arguments> plannedOutputsMissingARequiredValue() {
    var range = VideoDynamicRange.SDR;
    var encodeProfile = Optional.of(EncodeCodecProfile.H264_HIGH);
    var chroma = ChromaSubsampling.CHROMA_420;
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
            () ->
                PlannedVideoOutput.Encode.builder()
                    .dynamicRange(range)
                    .bitDepth(8)
                    .chromaSubsampling(chroma)
                    .build()),
        planWithoutValue(
            "an encode without a dynamic range",
            "dynamicRange",
            () ->
                PlannedVideoOutput.Encode.builder()
                    .codecProfile(encodeProfile)
                    .bitDepth(8)
                    .chromaSubsampling(chroma)
                    .build()),
        planWithoutValue(
            "an encode without a chroma subsampling",
            "chromaSubsampling",
            () ->
                PlannedVideoOutput.Encode.builder()
                    .codecProfile(encodeProfile)
                    .dynamicRange(range)
                    .bitDepth(8)
                    .build()),
        planWithoutValue(
            "a stream copy without a bit depth",
            "bitDepth",
            () ->
                new PlannedVideoOutput.StreamCopy(
                    range,
                    Optional.empty(),
                    OptionalInt.empty(),
                    null,
                    Optional.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())),
        planWithoutValue(
            "a stream copy without a chroma subsampling",
            "chromaSubsampling",
            () ->
                new PlannedVideoOutput.StreamCopy(
                    range,
                    Optional.empty(),
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    null,
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())),
        planWithoutValue(
            "an SDR encode without a codec profile",
            "codecProfile",
            () -> PlannedVideoOutput.Encode.sdr(null)));
  }

  private static Arguments planWithoutValue(
      String plan, String missingValue, ThrowingCallable build) {
    return Arguments.of(Named.of(plan, missingValue), build);
  }

  // #397: every encode writes 4:2:0, the subsampling that every HLS player decodes.
  @ParameterizedTest
  @EnumSource(
      value = ChromaSubsampling.class,
      names = {"CHROMA_420"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should refuse a planned encode when its chroma subsampling is not 4:2:0")
  void shouldRefuseAPlannedEncodeWhenItsChromaSubsamplingIsNot420(ChromaSubsampling chroma) {
    var encode = encodeBuilder().chromaSubsampling(chroma);

    assertThatThrownBy(encode::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4:2:0");
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
            "an 8-bit PQ H.264 encode",
            encodeBuilder()
                .dynamicRange(
                    new VideoDynamicRange(
                        CompatibleBase.PQ, Presence.ABSENT, new DolbyVision.Absent()))),
        Arguments.of(
            "a 10-bit PQ H.264 encode",
            encodeBuilder()
                .bitDepth(10)
                .dynamicRange(
                    new VideoDynamicRange(
                        CompatibleBase.PQ, Presence.ABSENT, new DolbyVision.Absent()))));
  }

  // #397: only a PQ encode writes HDR10 static metadata.
  @ParameterizedTest
  @EnumSource(
      value = CompatibleBase.class,
      names = {"SDR", "HLG"})
  @DisplayName("Should refuse static HDR metadata when the planned encode is not PQ")
  void shouldRefuseStaticHdrMetadataWhenThePlannedEncodeIsNotPq(CompatibleBase base) {
    var encode =
        encodeBuilder()
            .codecProfile(Optional.of(EncodeCodecProfile.AV1_MAIN))
            .bitDepth(10)
            .dynamicRange(new VideoDynamicRange(base, Presence.ABSENT, new DolbyVision.Absent()))
            .contentLightLevel(Optional.of(new ContentLightLevel(0, 0)));

    assertThatThrownBy(encode::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("static HDR metadata");
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
