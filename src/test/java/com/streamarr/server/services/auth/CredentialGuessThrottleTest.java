package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Guess Throttle Tests")
class CredentialGuessThrottleTest {

  private final MutableClock clock = new MutableClock();
  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(2).window(Duration.ofMinutes(15)).build(),
          clock);

  @Test
  @DisplayName("Should throttle Account password guesses when the budget is exhausted")
  void shouldThrottleAccountPasswordGuessesWhenBudgetIsExhausted() {
    var accountId = UUID.randomUUID();

    throttle.registerAccountPasswordAttempt(accountId);
    throttle.registerAccountPasswordAttempt(accountId);

    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should free the budget when the window passes")
  void shouldFreeBudgetWhenWindowPasses() {
    var accountId = UUID.randomUUID();
    throttle.registerAccountPasswordAttempt(accountId);
    throttle.registerAccountPasswordAttempt(accountId);

    clock.advance(Duration.ofMinutes(16));

    assertThatCode(() -> throttle.registerAccountPasswordAttempt(accountId))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should retain fresh attempts when older attempts expire")
  void shouldRetainFreshAttemptsWhenOlderAttemptsExpire() {
    var accountId = UUID.randomUUID();
    throttle.registerAccountPasswordAttempt(accountId);
    clock.advance(Duration.ofMinutes(10));
    throttle.registerAccountPasswordAttempt(accountId);
    clock.advance(Duration.ofMinutes(6));

    assertThatCode(() -> throttle.registerAccountPasswordAttempt(accountId))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should release full budget when blocked attempts occur before window expires")
  void shouldReleaseFullBudgetWhenBlockedAttemptsOccurBeforeWindowExpires() {
    var accountId = UUID.randomUUID();
    throttle.registerAccountPasswordAttempt(accountId);
    throttle.registerAccountPasswordAttempt(accountId);
    clock.advance(Duration.ofMinutes(14));
    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    clock.advance(Duration.ofMinutes(2));

    assertThatCode(
            () -> {
              throttle.registerAccountPasswordAttempt(accountId);
              throttle.registerAccountPasswordAttempt(accountId);
            })
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should evict expired budgets when swept")
  void shouldEvictExpiredBudgetsWhenSwept() {
    throttle.registerAccountPasswordAttempt(UUID.randomUUID());
    throttle.registerAccountPasswordAttempt(UUID.randomUUID());
    clock.advance(Duration.ofMinutes(16));

    assertThat(throttle.sweepExpired()).isEqualTo(2);
    assertThat(throttle.sweepExpired()).isZero();
  }
}
