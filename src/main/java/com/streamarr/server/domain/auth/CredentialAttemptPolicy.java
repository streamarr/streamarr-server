package com.streamarr.server.domain.auth;

import java.time.Duration;
import lombok.NonNull;

public sealed interface CredentialAttemptPolicy {

  record Unlimited() implements CredentialAttemptPolicy {}

  record Limited(
      int maximumFailures, @NonNull Duration failureWindow, @NonNull Duration throttleDuration)
      implements CredentialAttemptPolicy {

    private static final Duration MAXIMUM_DURATION = Duration.ofHours(24);

    public Limited {
      if (maximumFailures <= 0) {
        throw new IllegalArgumentException("maximumFailures must be positive");
      }
      requireValidDuration(failureWindow, "failureWindow");
      requireValidDuration(throttleDuration, "throttleDuration");
    }

    private static void requireValidDuration(Duration duration, String name) {
      if (duration.isZero() || duration.isNegative() || duration.compareTo(MAXIMUM_DURATION) > 0) {
        throw new IllegalArgumentException(name + " must be positive and no longer than 24 hours");
      }
    }
  }
}
