package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Subtitle Decision Tests")
class SubtitleDecisionTest {

  @ParameterizedTest
  @MethodSource("invalidSubtitleDecisions")
  @DisplayName("Should reject subtitle decision when values are structurally invalid")
  void shouldRejectSubtitleDecisionWhenValuesAreStructurallyInvalid(
      SubtitleMode mode,
      Optional<String> codec,
      OptionalInt streamIndex,
      Optional<String> language) {
    assertThatThrownBy(() -> new SubtitleDecision(mode, codec, streamIndex, language))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> invalidSubtitleDecisions() {
    return Stream.of(
        Arguments.of(null, Optional.empty(), OptionalInt.empty(), Optional.empty()),
        Arguments.of(SubtitleMode.EXCLUDE, null, OptionalInt.empty(), Optional.empty()),
        Arguments.of(SubtitleMode.EXCLUDE, Optional.empty(), null, Optional.empty()),
        Arguments.of(SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), null),
        Arguments.of(
            SubtitleMode.EXCLUDE, Optional.of("webvtt"), OptionalInt.empty(), Optional.empty()),
        Arguments.of(SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.of(0), Optional.empty()),
        Arguments.of(
            SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), Optional.of("eng")),
        Arguments.of(SubtitleMode.HLS, Optional.of(" "), OptionalInt.of(0), Optional.of("eng")),
        Arguments.of(
            SubtitleMode.HLS, Optional.of("webvtt"), OptionalInt.of(-1), Optional.of("eng")),
        Arguments.of(SubtitleMode.HLS, Optional.of("webvtt"), OptionalInt.of(0), Optional.of(" ")));
  }
}
