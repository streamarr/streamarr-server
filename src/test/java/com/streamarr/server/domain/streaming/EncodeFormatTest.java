package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
}
