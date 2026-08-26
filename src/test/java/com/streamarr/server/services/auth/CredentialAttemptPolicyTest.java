package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.CredentialAttemptHistory;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Attempt Policy Tests")
class CredentialAttemptPolicyTest {

  @Test
  @DisplayName("Should provide the standard limited policy for every credential kind")
  void shouldProvideTheStandardLimitedPolicyForEveryCredentialKind() {
    var provider = new StandardCredentialAttemptPolicyProvider();

    for (var kind : CredentialKind.values()) {
      assertThat(provider.policyFor(kind))
          .isEqualTo(
              new CredentialAttemptPolicy.Limited(
                  5, Duration.ofMinutes(15), Duration.ofMinutes(15)));
    }
  }

  @Test
  @DisplayName("Should support an unlimited credential policy")
  void shouldSupportAnUnlimitedCredentialPolicy() {
    CredentialAttemptPolicy policy = new CredentialAttemptPolicy.Unlimited();

    assertThat(policy).isInstanceOf(CredentialAttemptPolicy.Unlimited.class);
  }

  @Test
  @DisplayName("Should reject non-positive limited policy values")
  void shouldRejectNonPositiveLimitedPolicyValues() {
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    0, Duration.ofMinutes(15), Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maximumFailures must be positive");
    assertThatThrownBy(
            () -> new CredentialAttemptPolicy.Limited(5, Duration.ZERO, Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("failureWindow must be positive and no longer than 24 hours");
    assertThatThrownBy(
            () -> new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("throttleDuration must be positive and no longer than 24 hours");
  }

  @Test
  @DisplayName("Should reject limited policy durations longer than one day")
  void shouldRejectLimitedPolicyDurationsLongerThanOneDay() {
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    5, Duration.ofDays(1).plusNanos(1), Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("failureWindow must be positive and no longer than 24 hours");
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    5, Duration.ofMinutes(15), Duration.ofDays(1).plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("throttleDuration must be positive and no longer than 24 hours");
  }

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final CredentialAttemptPolicy.Limited STANDARD =
      new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

  @Test
  @DisplayName("Should admit when fewer than five failures and pending reservations hold slots")
  void shouldAdmitWhenFewerThanFiveFailuresAndPendingReservationsHoldSlots() {
    var history = new CredentialAttemptHistory(failuresAt(NOW, 3), List.of(NOW.plusSeconds(290)));

    assertThat(STANDARD.retryAfter(history, NOW.plusSeconds(10))).isEmpty();
  }

  @Test
  @DisplayName("Should lock out for the throttle duration from the fifth failure's completion")
  void shouldLockOutForThrottleDurationFromFifthFailuresCompletion() {
    var history = new CredentialAttemptHistory(failuresAt(NOW, 5), List.of());

    assertThat(STANDARD.retryAfter(history, NOW.plusSeconds(4).plusSeconds(1)))
        .contains(Duration.ofMinutes(15).minusSeconds(1));
  }

  @Test
  @DisplayName("Should not lock out when five failures span more than the window")
  void shouldNotLockOutWhenFiveFailuresSpanMoreThanWindow() {
    var spread =
        List.of(
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(2),
            NOW.plusSeconds(3),
            NOW.plus(Duration.ofMinutes(16)));
    var history = new CredentialAttemptHistory(spread, List.of());

    // Only the last failure is still inside the window.
    assertThat(STANDARD.retryAfter(history, NOW.plus(Duration.ofMinutes(16)).plusSeconds(1)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should block until the earliest slot frees when pending reservations fill capacity")
  void shouldBlockUntilEarliestSlotFreesWhenPendingReservationsFillCapacity() {
    var pendingExpiries = List.of(NOW.plusSeconds(300), NOW.plusSeconds(300));
    var history = new CredentialAttemptHistory(failuresAt(NOW, 3), pendingExpiries);

    assertThat(STANDARD.retryAfter(history, NOW.plusSeconds(1))).contains(Duration.ofSeconds(299));
  }

  @Test
  @DisplayName("Should admit again once the lockout has ended")
  void shouldAdmitAgainOnceLockoutHasEnded() {
    var history = new CredentialAttemptHistory(failuresAt(NOW, 5), List.of());

    assertThat(STANDARD.retryAfter(history, NOW.plusSeconds(4).plus(Duration.ofMinutes(15))))
        .isEmpty();
  }

  private static List<Instant> failuresAt(Instant first, int count) {
    return IntStream.range(0, count).mapToObj(first::plusSeconds).toList();
  }
}
