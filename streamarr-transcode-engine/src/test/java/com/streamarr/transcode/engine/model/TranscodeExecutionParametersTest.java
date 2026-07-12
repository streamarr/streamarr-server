package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Execution Parameters Tests")
class TranscodeExecutionParametersTest {

  @Test
  @DisplayName("Should preserve execution parameters when values are valid")
  void shouldPreserveExecutionParametersWhenValuesAreValid() {
    var parameters = new TranscodeExecutionParameters(30, 6, 23.976, 5, Duration.ofSeconds(45));

    assertThat(parameters.seekPosition()).isEqualTo(30);
    assertThat(parameters.segmentDuration()).isEqualTo(6);
    assertThat(parameters.framerate()).isEqualTo(23.976);
    assertThat(parameters.startNumber()).isEqualTo(5);
    assertThat(parameters.startupTimeout()).isEqualTo(Duration.ofSeconds(45));
  }

  @ParameterizedTest
  @MethodSource("invalidParameters")
  @DisplayName("Should reject execution parameters when values are invalid")
  void shouldRejectExecutionParametersWhenValuesAreInvalid(
      int seekPosition,
      int segmentDuration,
      double framerate,
      int startNumber,
      Duration startupTimeout) {
    assertThatThrownBy(
            () ->
                new TranscodeExecutionParameters(
                    seekPosition, segmentDuration, framerate, startNumber, startupTimeout))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> invalidParameters() {
    return Stream.of(
        Arguments.of(-1, 6, 23.976, 0, Duration.ofSeconds(45)),
        Arguments.of(0, 0, 23.976, 0, Duration.ofSeconds(45)),
        Arguments.of(0, 6, 0.0, 0, Duration.ofSeconds(45)),
        Arguments.of(0, 6, Double.NaN, 0, Duration.ofSeconds(45)),
        Arguments.of(0, 6, Double.POSITIVE_INFINITY, 0, Duration.ofSeconds(45)),
        Arguments.of(0, 6, 23.976, -1, Duration.ofSeconds(45)),
        Arguments.of(0, 6, 23.976, 0, null),
        Arguments.of(0, 6, 23.976, 0, Duration.ZERO),
        Arguments.of(0, 6, 23.976, 0, Duration.ofSeconds(-1)),
        Arguments.of(0, 6, 23.976, 0, Duration.ofSeconds(Long.MAX_VALUE)));
  }
}
