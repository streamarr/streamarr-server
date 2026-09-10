package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.support.WcagContrast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Contrast Adjustment Tests")
class ContrastAdjustmentTest {

  @ParameterizedTest
  @CsvSource({
    "0x000000, 0x000000, 3.0",
    "0xFFFFFF, 0xFFFFFF, 3.0",
    "0x202080, 0x101820, 3.0",
    "0x00E000, 0xFFFFFF, 4.5",
    "0x767676, 0x767676, 4.6",
    "0x000000, 0x000000, 21.0",
    "0xFFFFFF, 0xFFFFFF, 21.0"
  })
  @DisplayName("Should meet contrast in rounded RGB when color needs adjustment")
  void shouldMeetContrastInRoundedRgbWhenColorNeedsAdjustment(
      int color, int background, float minimumContrast) {
    var adjusted = ContrastAdjustment.adjust(color, background, minimumContrast).orElseThrow();

    assertThat(WcagContrast.ratio(adjusted, background)).isGreaterThanOrEqualTo(minimumContrast);
  }

  @Test
  @DisplayName("Should preserve the artwork color when contrast already passes")
  void shouldPreserveArtworkColorWhenContrastAlreadyPasses() {
    var color = 0x6FE0BF;

    assertThat(ContrastAdjustment.adjust(color, 0x0E3B34, 3f)).hasValue(color);
  }

  @Test
  @DisplayName("Should report unavailable contrast when neither black nor white can reach it")
  void shouldReportUnavailableContrastWhenNeitherBlackNorWhiteCanReachIt() {
    assertThat(ContrastAdjustment.adjust(0x808080, 0x808080, 7f)).isEmpty();
  }
}
