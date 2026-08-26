package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.CredentialAttemptHistory;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Credential Attempt Policy Tests")
class CredentialAttemptPolicyTest {

  @ParameterizedTest(name = "{0}")
  @EnumSource(CredentialKind.class)
  @DisplayName("Should provide the standard limited policy when any credential kind is requested")
  void shouldProvideStandardLimitedPolicyWhenAnyCredentialKindIsRequested(CredentialKind kind) {
    var provider = new StandardCredentialAttemptPolicyProvider();

    assertThat(provider.policyFor(kind))
        .isEqualTo(
            new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15)));
  }

  @ParameterizedTest(name = "{3}")
  @MethodSource("invalidLimitedPolicies")
  @DisplayName("Should reject a limited policy when a bound is not positive or exceeds one day")
  void shouldRejectLimitedPolicyWhenBoundIsNotPositiveOrExceedsOneDay(
      int maximumFailures, Duration failureWindow, Duration throttleDuration, String message) {
    assertThatThrownBy(
            () ->
                new CredentialAttemptPolicy.Limited(
                    maximumFailures, failureWindow, throttleDuration))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
  }

  private static Stream<Arguments> invalidLimitedPolicies() {
    var window = Duration.ofMinutes(15);
    var tooLong = Duration.ofDays(1).plusNanos(1);
    return Stream.of(
        Arguments.of(0, window, window, "maximumFailures must be positive"),
        Arguments.of(
            5, Duration.ZERO, window, "failureWindow must be positive and no longer than 24 hours"),
        Arguments.of(
            5,
            window,
            Duration.ZERO,
            "throttleDuration must be positive and no longer than 24 hours"),
        Arguments.of(
            5, tooLong, window, "failureWindow must be positive and no longer than 24 hours"),
        Arguments.of(
            5, window, tooLong, "throttleDuration must be positive and no longer than 24 hours"));
  }

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final CredentialAttemptPolicy.Limited STANDARD =
      new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

  @Test
  @DisplayName("Should admit when held slots are fewer than five")
  void shouldAdmitWhenHeldSlotsAreFewerThanFive() {
    var history = new CredentialAttemptHistory(failuresAt(NOW, 3), List.of(NOW.plusSeconds(290)));

    assertThat(STANDARD.retryAfter(history, NOW.plusSeconds(10))).isEmpty();
  }

  @Test
  @DisplayName("Should lock out for the throttle duration when the fifth failure completes")
  void shouldLockOutForThrottleDurationWhenFifthFailureCompletes() {
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

  @Test
  @DisplayName("Should admit exactly when the window closes on failures that filled it")
  void shouldAdmitExactlyWhenWindowClosesOnFailuresThatFilledIt() {
    var sameInstant = List.of(NOW, NOW, NOW, NOW, NOW);
    var history = new CredentialAttemptHistory(sameInstant, List.of());

    assertThat(STANDARD.retryAfter(history, NOW.plus(Duration.ofMinutes(15)))).isEmpty();
  }

  private static List<Instant> failuresAt(Instant first, int count) {
    return IntStream.range(0, count).mapToObj(first::plusSeconds).toList();
  }

  @Test
  @DisplayName("Should block when failures and pending reservations together fill capacity")
  void shouldBlockWhenFailuresAndPendingReservationsTogetherFillCapacity() {
    var history = new CredentialAttemptHistory(failuresAt(NOW, 4), List.of(NOW.plusSeconds(290)));

    assertThat(STANDARD.retryAfter(history, NOW.plusSeconds(10))).contains(Duration.ofSeconds(280));
  }

  @Test
  @DisplayName("Should accept a limited policy when its bounds sit exactly on the limits")
  void shouldAcceptLimitedPolicyWhenBoundsSitExactlyOnTheLimits() {
    assertThatCode(
            () -> new CredentialAttemptPolicy.Limited(1, Duration.ofDays(1), Duration.ofDays(1)))
        .doesNotThrowAnyException();
  }
}
