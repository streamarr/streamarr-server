package com.streamarr.server.exceptions;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Too Many Attempts Exception Tests")
class TooManyAttemptsExceptionTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejections")
  @DisplayName("Should reject a missing retry delay when a rejection is created")
  void shouldRejectMissingRetryDelayWhenRejectionIsCreated(
      String kind, Function<Duration, TooManyAttemptsException> rejection) {
    assertThatNullPointerException()
        .isThrownBy(() -> rejection.apply(null))
        .withMessageContaining("retryAfter");
  }

  private static Stream<Arguments> rejections() {
    return Stream.of(
        Arguments.of(
            "login",
            (Function<Duration, TooManyAttemptsException>) TooManyLoginAttemptsException::new),
        Arguments.of(
            "credential",
            (Function<Duration, TooManyAttemptsException>) TooManyCredentialAttemptsException::new),
        Arguments.of(
            "device",
            (Function<Duration, TooManyAttemptsException>) TooManyDeviceAttemptsException::new));
  }
}
