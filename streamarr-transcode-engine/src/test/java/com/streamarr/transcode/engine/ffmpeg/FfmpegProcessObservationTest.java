package com.streamarr.transcode.engine.ffmpeg;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Process Observation Tests")
class FfmpegProcessObservationTest {

  @ParameterizedTest
  @MethodSource("contradictoryObservations")
  @DisplayName("Should reject process observation when state contradicts exit code")
  void shouldRejectProcessObservationWhenStateContradictsExitCode(
      FfmpegProcessState state, OptionalInt exitCode) {
    assertThatThrownBy(() -> new FfmpegProcessObservation(state, exitCode))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> contradictoryObservations() {
    return Stream.of(
        Arguments.of(null, OptionalInt.empty()),
        Arguments.of(FfmpegProcessState.RUNNING, null),
        Arguments.of(FfmpegProcessState.RUNNING, OptionalInt.of(0)),
        Arguments.of(FfmpegProcessState.ABSENT, OptionalInt.of(0)),
        Arguments.of(FfmpegProcessState.COMPLETED, OptionalInt.empty()),
        Arguments.of(FfmpegProcessState.FAILED, OptionalInt.empty()),
        Arguments.of(FfmpegProcessState.STOPPED, OptionalInt.empty()));
  }
}
