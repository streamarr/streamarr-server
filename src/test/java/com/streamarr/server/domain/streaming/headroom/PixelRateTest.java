package com.streamarr.server.domain.streaming.headroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Pixel Rate Tests")
class PixelRateTest {

  @Test
  @DisplayName("Should multiply width, height and frame rate when a picture size and frame rate are given")
  void shouldMultiplyWidthHeightAndFrameRateWhenAPictureSizeAndFrameRateAreGiven() {
    var rate = PixelRate.of(1920, 1080, 24_000.0 / 1_001);

    assertThat(rate.pixelsPerSecond()).isCloseTo(1920 * 1080 * 24_000.0 / 1_001, within(1e-6));
  }

  @Test
  @DisplayName("Should rate a 60 fps source 2.5 times a 24 fps source of the same size when both are 1080p")
  void shouldRateA60FpsSource25TimesA24FpsSourceOfTheSameSizeWhenBothAre1080p() {
    var film = PixelRate.of(1920, 1080, 24);
    var sport = PixelRate.of(1920, 1080, 60);

    assertThat(sport.pixelsPerSecond() / film.pixelsPerSecond()).isCloseTo(2.5, within(1e-12));
  }

  @ParameterizedTest(name = "{0}x{1} at {2} fps")
  @CsvSource({"0,1080,24", "1920,-1,24", "1920,1080,0", "1920,1080,NaN", "1920,1080,Infinity"})
  @DisplayName("Should reject the pixel rate when a factor is not a positive finite number")
  void shouldRejectThePixelRateWhenAFactorIsNotAPositiveFiniteNumber(
      int width, int height, double framesPerSecond) {
    assertThatThrownBy(() -> PixelRate.of(width, height, framesPerSecond))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
