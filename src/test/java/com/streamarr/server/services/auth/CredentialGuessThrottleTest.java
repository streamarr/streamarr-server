package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.GatedClock;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Guess Throttle Tests")
class CredentialGuessThrottleTest {

  private final MutableClock clock = new MutableClock();
  private final AuthThrottleProperties properties =
      AuthThrottleProperties.builder().maxAttempts(2).window(Duration.ofMinutes(15)).build();
  private final CredentialGuessThrottle throttle = new CredentialGuessThrottle(properties, clock);

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
  @DisplayName("Should keep Account password budgets independent when the Account changes")
  void shouldKeepAccountPasswordBudgetsIndependentWhenAccountChanges() {
    var exhaustedAccountId = UUID.randomUUID();
    throttle.registerAccountPasswordAttempt(exhaustedAccountId);
    throttle.registerAccountPasswordAttempt(exhaustedAccountId);

    assertThatCode(() -> throttle.registerAccountPasswordAttempt(UUID.randomUUID()))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(exhaustedAccountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should throttle Profile PIN guesses when the budget is exhausted")
  void shouldThrottleProfilePinGuessesWhenBudgetIsExhausted() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerProfilePinAttempt(accountId, profileId);

    assertThatThrownBy(() -> throttle.registerProfilePinAttempt(accountId, profileId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should serialize attempts when they concurrently use the same Account budget")
  void shouldSerializeAttemptsWhenConcurrentlyUsingSameAccountBudget() throws Exception {
    var accountId = UUID.randomUUID();
    var gatedClock = new GatedClock(clock);
    var concurrentThrottle = new CredentialGuessThrottle(properties, gatedClock);
    concurrentThrottle.registerAccountPasswordAttempt(accountId);
    gatedClock.blockNextCall();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> register(concurrentThrottle, accountId));
      assertThat(gatedClock.awaitBlockedCall()).isTrue();

      var secondThread = new AtomicReference<Thread>();
      var secondStarted = new CountDownLatch(1);
      var second =
          executor.submit(
              () -> {
                secondThread.set(Thread.currentThread());
                secondStarted.countDown();
                return register(concurrentThrottle, accountId);
              });
      assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

      try {
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> second.isDone() || secondThread.get().getState() == Thread.State.BLOCKED);
        assertThat(second)
            .as("same-Account attempt waits for the in-flight reservation")
            .isNotDone();
      } finally {
        gatedClock.releaseBlockedCall();
      }

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  @DisplayName(
      "Should keep budgets independent when credentials use a Profile PIN and Account password")
  void shouldKeepBudgetsIndependentWhenCredentialsUseProfilePinAndAccountPassword() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerProfilePinAttempt(accountId, profileId);

    assertThatCode(
            () -> {
              throttle.registerProfilePinAttempt(accountId, UUID.randomUUID());
              throttle.registerAccountPasswordAttempt(accountId);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reset only the successful budget when a credential verifies")
  void shouldResetOnlySuccessfulBudgetWhenCredentialVerifies() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerProfilePinAttempt(accountId, profileId);
    throttle.registerAccountPasswordAttempt(accountId);
    throttle.registerAccountPasswordAttempt(accountId);

    throttle.resetProfilePinAttempts(accountId, profileId);

    assertThatCode(() -> throttle.registerProfilePinAttempt(accountId, profileId))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should keep opaque-code budgets independent when public identifiers differ")
  void shouldKeepOpaqueCodeBudgetsIndependentWhenPublicIdentifiersDiffer() {
    throttle.registerCodeGuess("first-public-id");
    throttle.registerCodeGuess("first-public-id");

    assertThatCode(() -> throttle.registerCodeGuess("second-public-id")).doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerCodeGuess("first-public-id"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should reset only the matching opaque-code budget when a code verifies")
  void shouldResetOnlyMatchingOpaqueCodeBudgetWhenCodeVerifies() {
    throttle.registerCodeGuess("successful-public-id");
    throttle.registerCodeGuess("successful-public-id");
    throttle.registerCodeGuess("blocked-public-id");
    throttle.registerCodeGuess("blocked-public-id");

    throttle.resetCodeGuesses("successful-public-id");

    assertThatCode(() -> throttle.registerCodeGuess("successful-public-id"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerCodeGuess("blocked-public-id"))
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
    throttle.registerProfilePinAttempt(UUID.randomUUID(), UUID.randomUUID());
    throttle.registerAccountPasswordAttempt(UUID.randomUUID());
    clock.advance(Duration.ofMinutes(16));

    assertThat(throttle.sweepExpired()).isEqualTo(2);
    assertThat(throttle.sweepExpired()).isZero();
  }

  @Test
  @DisplayName("Should retain live Account password budgets when swept")
  void shouldRetainLiveAccountPasswordBudgetsWhenSwept() {
    var accountId = UUID.randomUUID();
    throttle.registerAccountPasswordAttempt(accountId);

    assertThat(throttle.sweepExpired()).isZero();
    throttle.registerAccountPasswordAttempt(accountId);
    assertThatThrownBy(() -> throttle.registerAccountPasswordAttempt(accountId))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  private static boolean register(CredentialGuessThrottle throttle, UUID accountId) {
    try {
      throttle.registerAccountPasswordAttempt(accountId);
      return true;
    } catch (TooManyCredentialAttemptsException _) {
      return false;
    }
  }
}
