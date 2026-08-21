package com.streamarr.server.exceptions;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Too Many Device Attempts Exception Tests")
class TooManyDeviceAttemptsExceptionTest {

  @Test
  @DisplayName("Should reject a missing retry delay when creating the exception")
  void shouldRejectMissingRetryDelayWhenCreatingException() {
    assertThatNullPointerException()
        .isThrownBy(() -> new TooManyDeviceAttemptsException(null))
        .withMessage("retryAfter");
  }
}
