package com.streamarr.server.domain.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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

    /**
     * Empty when an attempt at {@code now} is admitted, otherwise how long until capacity frees
     * (ADR 0028): completing the last failure of a full run inside one window begins a lockout for
     * the throttle duration; until a run completes, each failure inside the window and each fresh
     * pending reservation holds one slot.
     */
    public Optional<Duration> retryAfter(CredentialAttemptHistory history, Instant now) {
      return lockoutEnd(history.failures(), now)
          .or(() -> capacityFreesAt(history, now))
          .map(freesAt -> Duration.between(now, freesAt));
    }

    /** The end of the newest full run of failures inside one window, while it is still running. */
    private Optional<Instant> lockoutEnd(List<Instant> failures, Instant now) {
      for (var last = failures.size() - 1; last >= maximumFailures - 1; last--) {
        var first = failures.get(last - maximumFailures + 1);
        var threshold = failures.get(last);
        if (Duration.between(first, threshold).compareTo(failureWindow) > 0) {
          continue;
        }

        var lockoutEnd = threshold.plus(throttleDuration);
        if (lockoutEnd.isAfter(now)) {
          return Optional.of(lockoutEnd);
        }

        // Every earlier run completed earlier, so its lockout ended earlier still.
        return Optional.empty();
      }

      return Optional.empty();
    }

    private Optional<Instant> capacityFreesAt(CredentialAttemptHistory history, Instant now) {
      var windowStart = now.minus(failureWindow);
      // Strictly after: a failure exactly one window old frees its slot at that instant, which is
      // the moment a blocked client was told to retry.
      var recentFailures =
          history.failures().stream().filter(failure -> failure.isAfter(windowStart)).toList();
      if (recentFailures.size() + history.pendingExpiries().size() < maximumFailures) {
        return Optional.empty();
      }

      return Stream.concat(
              recentFailures.stream().map(failure -> failure.plus(failureWindow)),
              history.pendingExpiries().stream())
          .min(Instant::compareTo);
    }

    private static void requireValidDuration(Duration duration, String name) {
      if (duration.isZero() || duration.isNegative() || duration.compareTo(MAXIMUM_DURATION) > 0) {
        throw new IllegalArgumentException(name + " must be positive and no longer than 24 hours");
      }
    }
  }
}
