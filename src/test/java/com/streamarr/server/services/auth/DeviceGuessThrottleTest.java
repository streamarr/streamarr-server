package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Guess Throttle Tests")
class DeviceGuessThrottleTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final MutableClock clock = new MutableClock(currentTime);

  private final DeviceAuthProperties properties =
      DeviceAuthProperties.builder()
          .codeTtl(Duration.ofMinutes(10))
          .pollIntervalSeconds(5)
          .verificationPath("/link")
          .maxOutstandingCodes(50)
          .maxGuessAttempts(3)
          .guessWindow(Duration.ofMinutes(15))
          .sweepInterval(Duration.ofMinutes(15))
          .build();

  private final DeviceGuessThrottle throttle = new DeviceGuessThrottle(properties, clock);

  @Test
  @DisplayName("Should allow attempts up to the configured budget")
  void shouldAllowAttemptsUpToConfiguredBudget() {
    var accountId = UUID.randomUUID();

    assertThatCode(
            () -> {
              throttle.registerAttempt(accountId);
              throttle.registerAttempt(accountId);
              throttle.registerAttempt(accountId);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should report the wait until a slot frees when the budget is exhausted")
  void shouldReportWaitUntilSlotFreesWhenBudgetExhausted() {
    var accountId = UUID.randomUUID();
    exhaustBudget(accountId);

    advanceClock(Duration.ofMinutes(5));

    assertThatThrownBy(() -> throttle.registerAttempt(accountId))
        .isInstanceOf(TooManyDeviceAttemptsException.class)
        .satisfies(
            e ->
                assertThat(((TooManyDeviceAttemptsException) e).getRetryAfter())
                    .isEqualTo(Duration.ofMinutes(10)));
  }

  @Test
  @DisplayName("Should let attempts through again once the window has passed")
  void shouldLetAttemptsThroughAgainOnceWindowHasPassed() {
    var accountId = UUID.randomUUID();
    exhaustBudget(accountId);

    advanceClock(Duration.ofMinutes(16));

    assertThatCode(() -> throttle.registerAttempt(accountId)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should budget each account separately")
  void shouldBudgetEachAccountSeparately() {
    var exhausted = UUID.randomUUID();
    exhaustBudget(exhausted);

    assertThatCode(() -> throttle.registerAttempt(UUID.randomUUID())).doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAttempt(exhausted))
        .isInstanceOf(TooManyDeviceAttemptsException.class);
  }

  @Test
  @DisplayName("Should not extend a lockout with attempts made while already blocked")
  void shouldNotExtendLockoutWithAttemptsMadeWhileAlreadyBlocked() {
    var accountId = UUID.randomUUID();
    exhaustBudget(accountId);

    advanceClock(Duration.ofMinutes(14));
    assertThatThrownBy(() -> throttle.registerAttempt(accountId))
        .isInstanceOf(TooManyDeviceAttemptsException.class);

    // A blocked attempt reserves nothing, so hostile traffic cannot hold a victim out forever.
    advanceClock(Duration.ofMinutes(2));
    assertThatCode(() -> throttle.registerAttempt(accountId)).doesNotThrowAnyException();
  }

  private void exhaustBudget(UUID accountId) {
    for (var attempt = 0; attempt < properties.maxGuessAttempts(); attempt++) {
      throttle.registerAttempt(accountId);
    }
  }

  private void advanceClock(Duration duration) {
    currentTime.updateAndGet(instant -> instant.plus(duration));
  }
}
