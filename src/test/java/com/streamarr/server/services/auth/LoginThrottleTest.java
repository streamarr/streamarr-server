package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Login Throttle Tests")
class LoginThrottleTest {

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final String EMAIL = "user@example.com";
  private static final String SOURCE = "203.0.113.7";

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
  private final MutableClock clock = new MutableClock(currentTime);

  private final LoginThrottle throttle =
      new LoginThrottle(
          AuthThrottleProperties.builder().maxAttempts(MAX_ATTEMPTS).window(WINDOW).build(), clock);

  @Test
  @DisplayName("Should block sixth attempt when email budget exhausted")
  void shouldBlockSixthAttemptWhenEmailBudgetExhausted() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL, "src-" + i);
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, "src-fresh"))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should block sixth attempt when source budget exhausted across emails")
  void shouldBlockSixthAttemptWhenSourceBudgetExhaustedAcrossEmails() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt("user-" + i + "@example.com", SOURCE);
    }

    assertThatThrownBy(() -> throttle.registerAttempt("fresh@example.com", SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should allow exactly max attempts when burst arrives concurrently")
  void shouldAllowExactlyMaxAttemptsWhenBurstArrivesConcurrently() throws Exception {
    var burstSize = 20;
    var allowed = new AtomicInteger();
    var barrier = new CyclicBarrier(burstSize);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var attempts = new ArrayList<Future<?>>();
      for (int i = 0; i < burstSize; i++) {
        attempts.add(
            executor.submit(
                () -> {
                  barrier.await();
                  try {
                    throttle.registerAttempt(EMAIL, SOURCE);
                    allowed.incrementAndGet();
                  } catch (TooManyLoginAttemptsException _) {
                    // Blocked attempts are the expected majority.
                  }
                  return null;
                }));
      }
      for (var attempt : attempts) {
        attempt.get();
      }
    }

    assertThat(allowed).hasValue(MAX_ATTEMPTS);
  }

  @Test
  @DisplayName("Should return email slot when source budget blocks the attempt")
  void shouldReturnEmailSlotWhenSourceBudgetBlocksTheAttempt() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt("other-" + i + "@example.com", SOURCE);
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);

    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      var freshSource = "src-" + i;
      assertThatCode(() -> throttle.registerAttempt(EMAIL, freshSource)).doesNotThrowAnyException();
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, "src-fresh"))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should keep earlier attempts when returned slot not the only one")
  void shouldKeepEarlierAttemptsWhenReturnedSlotNotTheOnlyOne() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt("other-" + i + "@example.com", SOURCE);
    }
    throttle.registerAttempt(EMAIL, "src-earlier");

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);

    for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
      var freshSource = "src-" + i;
      assertThatCode(() -> throttle.registerAttempt(EMAIL, freshSource)).doesNotThrowAnyException();
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, "src-fresh"))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should allow attempts again when window passes")
  void shouldAllowAttemptsAgainWhenWindowPasses() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL, SOURCE);
    }

    currentTime.updateAndGet(instant -> instant.plus(WINDOW).plusSeconds(1));

    assertThatCode(() -> throttle.registerAttempt(EMAIL, SOURCE)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should still throttle when window exactly elapsed")
  void shouldStillThrottleWhenWindowExactlyElapsed() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL, SOURCE);
    }

    currentTime.updateAndGet(instant -> instant.plus(WINDOW));

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should not extend lockout window when blocked attempts continue")
  void shouldNotExtendLockoutWindowWhenBlockedAttemptsContinue() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL, SOURCE);
    }

    currentTime.updateAndGet(instant -> instant.plus(WINDOW).minusSeconds(1));
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, SOURCE))
          .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    currentTime.updateAndGet(instant -> instant.plusSeconds(2));

    assertThatCode(() -> throttle.registerAttempt(EMAIL, SOURCE)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should restore full budget when reset after failures")
  void shouldRestoreFullBudgetWhenResetAfterFailures() {
    for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
      throttle.registerAttempt(EMAIL, SOURCE);
    }

    throttle.reset(EMAIL, SOURCE);

    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      var freshSource = "post-src-" + i;
      assertThatCode(() -> throttle.registerAttempt(EMAIL, freshSource)).doesNotThrowAnyException();
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, "post-src-final"))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should keep other emails source failures when reset")
  void shouldKeepOtherEmailsSourceFailuresWhenReset() {
    for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
      throttle.registerAttempt("sprayed-" + i + "@example.com", SOURCE);
    }
    throttle.registerAttempt(EMAIL, SOURCE);

    throttle.reset(EMAIL, SOURCE);

    assertThatCode(() -> throttle.registerAttempt("fresh@example.com", SOURCE))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAttempt("another@example.com", SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should throttle by email alone when source missing")
  void shouldThrottleByEmailAloneWhenSourceMissing() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL, null);
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, null))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should throttle by source alone when email missing")
  void shouldThrottleBySourceAloneWhenEmailMissing() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(null, SOURCE);
    }

    assertThatThrownBy(() -> throttle.registerAttempt(null, SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);

    assertThatCode(() -> throttle.reset(null, null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should evict sprayed keys when sweeping after window")
  void shouldEvictSprayedKeysWhenSweepingAfterWindow() {
    var sprayedKeys = 50;
    for (int i = 0; i < sprayedKeys; i++) {
      throttle.registerAttempt("sprayed-" + i + "@example.com", "src-" + i);
    }

    currentTime.updateAndGet(instant -> instant.plus(WINDOW).plusSeconds(1));

    assertThat(throttle.sweepExpired()).isEqualTo(sprayedKeys * 2);
    assertThat(throttle.sweepExpired()).isZero();
  }

  @Test
  @DisplayName("Should keep live budgets when sweeping within window")
  void shouldKeepLiveBudgetsWhenSweepingWithinWindow() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL, SOURCE);
    }

    assertThat(throttle.sweepExpired()).isZero();

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, SOURCE))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should treat email case-insensitively when throttling")
  void shouldTreatEmailCaseInsensitivelyWhenThrottling() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      throttle.registerAttempt(EMAIL.toUpperCase(java.util.Locale.ROOT), "src-" + i);
    }

    assertThatThrownBy(() -> throttle.registerAttempt(EMAIL, "src-fresh"))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }
}
