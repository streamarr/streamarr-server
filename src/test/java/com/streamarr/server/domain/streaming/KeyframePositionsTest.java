package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  private static KeyframePositions.KeyframePositionsBuilder validPositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .source(KeyframePositionSource.CONTAINER_INDEX)
        .keyframes(List.of(new KeyframePosition(0, 0)));
  }
}
