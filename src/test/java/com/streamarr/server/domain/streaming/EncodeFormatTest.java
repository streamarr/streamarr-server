package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// #397: an encode is 8-bit or 10-bit, and a PQ or HLG encode is 10-bit.
@Tag("UnitTest")
@DisplayName("Encode Format Tests")
class EncodeFormatTest {

  @ParameterizedTest
  @ValueSource(ints = {0, 9, 12})
  @DisplayName("Should refuse an encode format when its bit depth is neither 8 nor 10")
  void shouldRefuseAnEncodeFormatWhenItsBitDepthIsNeither8Nor10(int bitDepth) {
    assertThatThrownBy(() -> new EncodeFormat(VideoRange.SDR, bitDepth))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("8 or 10");
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoRange.class,
      names = {"PQ", "HLG"})
  @DisplayName("Should refuse an 8-bit encode format when its range is PQ or HLG")
  void shouldRefuseAn8BitEncodeFormatWhenItsRangeIsPqOrHlg(VideoRange range) {
    assertThatThrownBy(() -> new EncodeFormat(range, 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10-bit");
  }

  @ParameterizedTest
  @CsvSource({"SDR, SDR, false", "PQ, PQ, true", "HLG, HLG, true"})
  @DisplayName("Should signal the compatible base of its range when the encode writes it")
  void shouldSignalTheCompatibleBaseOfItsRangeWhenTheEncodeWritesIt(
      VideoRange range, CompatibleBase base, boolean hdr) {
    var format = new EncodeFormat(range, 10);

    assertThat(format.compatibleBase()).isEqualTo(base);
    assertThat(format.isHdr()).isEqualTo(hdr);
  }

  @Test
  @DisplayName("Should write 10 bits when the encode keeps an HDR range")
  void shouldWrite10BitsWhenTheEncodeKeepsAnHdrRange() {
    assertThat(EncodeFormat.hdr(VideoRange.PQ)).isEqualTo(new EncodeFormat(VideoRange.PQ, 10));
  }

  // A worker lists a feature on an encoder only after its own test encode wrote that output, and a
  // Dolby Vision source without a compatible base needs its RPUs reshaped into PQ first (#397).
  @ParameterizedTest(name = "{0} from a {1} source")
  @MethodSource("encodeFeaturesByFormat")
  @DisplayName(
      "Should need the encode features that write its output when an encoder writes the format"
          + " from the source's compatible base")
  void shouldNeedTheEncodeFeaturesThatWriteItsOutputWhenAnEncoderWritesTheFormat(
      EncodeFormat format, CompatibleBase sourceBase, Set<VideoEncodeFeature> features) {
    assertThat(format.encodeFeaturesFrom(sourceBase)).isEqualTo(features);
  }

  static Stream<Arguments> encodeFeaturesByFormat() {
    return Stream.of(
        Arguments.of(EncodeFormat.SDR_8_BIT, CompatibleBase.PQ, Set.of()),
        Arguments.of(
            EncodeFormat.SDR_10_BIT, CompatibleBase.SDR, Set.of(VideoEncodeFeature.TEN_BIT)),
        Arguments.of(
            EncodeFormat.hdr(VideoRange.PQ), CompatibleBase.PQ, Set.of(VideoEncodeFeature.PQ)),
        Arguments.of(
            EncodeFormat.hdr(VideoRange.PQ),
            CompatibleBase.NONE,
            Set.of(VideoEncodeFeature.PQ, VideoEncodeFeature.DOLBY_VISION_TO_PQ)),
        Arguments.of(
            EncodeFormat.hdr(VideoRange.HLG), CompatibleBase.HLG, Set.of(VideoEncodeFeature.HLG)));
  }
}
