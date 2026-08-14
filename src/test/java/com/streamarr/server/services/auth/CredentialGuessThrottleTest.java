package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Guess Throttle Tests")
class CredentialGuessThrottleTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(2).window(Duration.ofMinutes(15)).build(),
          new MutableClock(currentTime));

  @Test
  @DisplayName("Should throttle profile PIN guesses after the configured budget is exhausted")
  void shouldThrottleProfilePinGuessesAfterConfiguredBudgetExhausted() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerProfilePinAttempt(accountId, profileId);

    assertThatThrownBy(() -> throttle.registerProfilePinAttempt(accountId, profileId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should keep profile PIN and administrator password budgets independent")
  void shouldKeepProfilePinAndAdministratorPasswordBudgetsIndependent() {
    var accountId = UUID.randomUUID();
    var firstProfileId = UUID.randomUUID();
    throttle.registerProfilePinAttempt(accountId, firstProfileId);
    throttle.registerProfilePinAttempt(accountId, firstProfileId);

    assertThatCode(
            () -> {
              throttle.registerProfilePinAttempt(accountId, UUID.randomUUID());
              throttle.registerServerAdminPasswordAttempt(accountId);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reset only the credential budget that verified successfully")
  void shouldResetOnlyCredentialBudgetThatVerifiedSuccessfully() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerServerAdminPasswordAttempt(accountId);
    throttle.registerServerAdminPasswordAttempt(accountId);

    throttle.resetProfilePinAttempts(accountId, profileId);

    assertThatCode(() -> throttle.registerProfilePinAttempt(accountId, profileId))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerServerAdminPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should evict expired credential budgets")
  void shouldEvictExpiredCredentialBudgets() {
    throttle.registerProfilePinAttempt(UUID.randomUUID(), UUID.randomUUID());
    throttle.registerServerAdminPasswordAttempt(UUID.randomUUID());
    currentTime.updateAndGet(instant -> instant.plus(Duration.ofMinutes(16)));

    assertThat(throttle.sweepExpired()).isEqualTo(2);
    assertThat(throttle.sweepExpired()).isZero();
  }
}
