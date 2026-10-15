package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Keyframe Positions Tests")
class KeyframePositionsTest {

  @ParameterizedTest
  @CsvSource({"-1,1,90000", "0,0,90000", "0,1,0", "0,-1,90000", "0,1,-90000"})
  @DisplayName("Should reject positions when the stream index or the time base is invalid")
  void shouldRejectPositionsWhenStreamIndexOrTimeBaseIsInvalid(
      int streamIndex, int numerator, int denominator) {
    var positions =
        validPositions()
            .streamIndex(streamIndex)
            .timeBaseNumerator(numerator)
            .timeBaseDenominator(denominator);

    assertThatThrownBy(positions::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject positions when they hold no keyframe")
  void shouldRejectPositionsWhenTheyHoldNoKeyframe() {
    var positions = validPositions().keyframes(List.of());

    assertThatThrownBy(positions::build).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "144, FLOOR, PT6.006S",
    "1, FLOOR, PT0.041708333S",
    "1, CEILING, PT0.041708334S",
    "-1, FLOOR, PT-0.041708334S",
    "-1, CEILING, PT-0.041708333S"
  })
  @DisplayName("Should convert ticks to time in whole nanoseconds when rounding a time base tick")
  void shouldConvertTicksToTimeInWholeNanosecondsWhenRoundingATimeBaseTick(
      long ticks, RoundingMode rounding, Duration expected) {
    var positions = validPositions().timeBaseNumerator(1001).timeBaseDenominator(24_000).build();

    assertThat(positions.timeOf(ticks, rounding)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should refuse a time that does not fit a duration")
  void shouldRefuseATimeThatDoesNotFitADuration() {
    var positions =
        validPositions().timeBaseNumerator(Integer.MAX_VALUE).timeBaseDenominator(1).build();

    assertThatThrownBy(() -> positions.timeOf(Long.MAX_VALUE, RoundingMode.FLOOR))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName("Should reject a time conversion without a rounding mode")
  void shouldRejectATimeConversionWithoutARoundingMode() {
    var positions = validPositions().build();

    assertThatNullPointerException().isThrownBy(() -> positions.timeOf(0, null));
  }

  private static KeyframePositions.KeyframePositionsBuilder validPositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .source(KeyframePositionSource.CONTAINER_INDEX)
        .keyframes(List.of(new KeyframePosition(0, 0)));
  }
}
