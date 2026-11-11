package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Lower Variant Encode Tests")
class LowerVariantEncodeTest {

  // #58: H.265 Main 10 holds only 10 bits, and H.264 High and H.265 Main hold only 8, so a job
  // that paired one with another bit depth would write a profile that its playlist entry does not
  // name.
  @ParameterizedTest(name = "{0} at {1}")
  @MethodSource("codecProfilesAndFormatsTheyCannotHold")
  @DisplayName(
      "Should refuse a lower variant encode when its codec profile cannot hold the bit depth of its"
          + " format")
  void shouldRefuseALowerVariantEncodeWhenItsCodecProfileCannotHoldTheBitDepthOfItsFormat(
      EncodeCodecProfile codecProfile, EncodeFormat format) {
    assertThatThrownBy(() -> new LowerVariantEncode(codecProfile, format))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must hold the bit depth");
  }

  static Stream<Arguments> codecProfilesAndFormatsTheyCannotHold() {
    return Stream.of(
        Arguments.of(EncodeCodecProfile.HEVC_MAIN_10, EncodeFormat.SDR_8_BIT),
        Arguments.of(EncodeCodecProfile.HEVC_MAIN, EncodeFormat.SDR_10_BIT),
        Arguments.of(EncodeCodecProfile.HEVC_MAIN, EncodeFormat.hdr(VideoRange.PQ)),
        Arguments.of(EncodeCodecProfile.H264_HIGH, EncodeFormat.hdr(VideoRange.HLG)));
  }
}
