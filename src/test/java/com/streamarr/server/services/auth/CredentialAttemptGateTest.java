package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.CredentialAttemptNotPendingException;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.RetryAfterAware;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.support.LogCapture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

@Tag("UnitTest")
@DisplayName("Credential Attempt Gate Tests")
class CredentialAttemptGateTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final CredentialAttemptTarget LOGIN_TARGET =
      CredentialAttemptTarget.builder()
          .kind(CredentialKind.ACCOUNT_LOGIN)
          .accountId(ACCOUNT_ID)
          .ipAddress("192.0.2.30")
          .build();

  private final FakeCredentialAttemptRepository repository = new FakeCredentialAttemptRepository();
  private final CredentialAttemptGate gate = repository.gate(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("Should fail closed when reserving an attempt cannot reach the database")
  void shouldFailClosedWhenReservingAttemptCannotReachDatabase() {
    repository.failWith(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(() -> gate.reserve(LOGIN_TARGET))
        .isInstanceOf(CredentialAttemptUnavailableException.class)
        .hasCauseInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  @DisplayName("Should fail closed when reserving an attempt cannot open a transaction")
  void shouldFailClosedWhenReservingAttemptCannotOpenTransaction() {
    repository.failWith(new CannotCreateTransactionException("pool exhausted"));

    assertThatThrownBy(() -> gate.reserve(LOGIN_TARGET))
        .isInstanceOf(CredentialAttemptUnavailableException.class)
        .hasCauseInstanceOf(CannotCreateTransactionException.class);
  }

  @Test
  @DisplayName("Should fail closed when completing an attempt cannot reach the database")
  void shouldFailClosedWhenCompletingAttemptCannotReachDatabase() {
    var reservation = gate.reserve(LOGIN_TARGET);
    repository.failWith(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(() -> gate.complete(reservation, CredentialAttemptResult.SUCCEEDED))
        .isInstanceOf(CredentialAttemptUnavailableException.class)
        .hasCauseInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  @DisplayName(
      "Should pass the not-pending failure through when the reservation is already completed")
  void shouldPassNotPendingFailureThroughWhenReservationIsAlreadyCompleted() {
    var reservation = gate.reserve(LOGIN_TARGET);
    gate.complete(reservation, CredentialAttemptResult.FAILED);

    assertThatThrownBy(() -> gate.complete(reservation, CredentialAttemptResult.SUCCEEDED))
        .isInstanceOf(CredentialAttemptNotPendingException.class);
  }

  @Test
  @DisplayName("Should log an error naming the target when the journal is unavailable")
  void shouldLogErrorNamingTargetWhenJournalIsUnavailable() {
    repository.failWith(new DataAccessResourceFailureException("database unavailable"));

    try (var logs = LogCapture.forClass(CredentialAttemptGate.class)) {
      assertThatThrownBy(() -> gate.reserve(LOGIN_TARGET))
          .isInstanceOf(CredentialAttemptUnavailableException.class);

      assertThat(logs.events())
          .anyMatch(
              event ->
                  event.getLevel() == Level.ERROR
                      && event.getFormattedMessage().contains("ACCOUNT_LOGIN")
                      && event.getFormattedMessage().contains(ACCOUNT_ID.toString())
                      && event.getThrowableProxy() != null);
    }
  }

  @Test
  @DisplayName("Should log a warning naming the target when a reservation is blocked")
  void shouldLogWarningNamingTargetWhenReservationIsBlocked() {
    repository.rejectReservations(Duration.ofSeconds(42));

    try (var logs = LogCapture.forClass(CredentialAttemptGate.class)) {
      assertThatThrownBy(() -> gate.reserve(LOGIN_TARGET))
          .isInstanceOf(TooManyLoginAttemptsException.class);

      assertThat(logs.events())
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("ACCOUNT_LOGIN")
                      && event.getFormattedMessage().contains(ACCOUNT_ID.toString())
                      && event.getFormattedMessage().contains("PT42S"));
    }
  }

  @Test
  @DisplayName("Should refuse a login with its retry delay when the Account is blocked")
  void shouldRefuseLoginWithRetryDelayWhenAccountIsBlocked() {
    repository.rejectReservations(Duration.ofSeconds(42));

    assertThatThrownBy(() -> gate.reserve(LOGIN_TARGET))
        .isInstanceOf(TooManyLoginAttemptsException.class)
        .extracting(failure -> ((RetryAfterAware) failure).retryAfter())
        .isEqualTo(Duration.ofSeconds(42));
  }

  @Test
  @DisplayName("Should refuse a Profile PIN with its retry delay when the Profile is blocked")
  void shouldRefuseProfilePinWithRetryDelayWhenProfileIsBlocked() {
    repository.rejectReservations(Duration.ofSeconds(42));
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PROFILE_PIN)
            .accountId(ACCOUNT_ID)
            .profileId(UUID.fromString("20000000-0000-0000-0000-000000000001"))
            .ipAddress("192.0.2.30")
            .build();

    assertThatThrownBy(() -> gate.reserve(target))
        .isInstanceOf(TooManyCredentialAttemptsException.class)
        .extracting(failure -> ((RetryAfterAware) failure).retryAfter())
        .isEqualTo(Duration.ofSeconds(42));
  }

  @Test
  @DisplayName("Should refuse a pairing code with its retry delay when the approver is blocked")
  void shouldRefusePairingCodeWithRetryDelayWhenApproverIsBlocked() {
    repository.rejectReservations(Duration.ofSeconds(42));
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.DEVICE_PAIRING_CODE)
            .accountId(ACCOUNT_ID)
            .ipAddress("192.0.2.30")
            .build();

    assertThatThrownBy(() -> gate.reserve(target))
        .isInstanceOf(TooManyDeviceAttemptsException.class)
        .extracting(failure -> ((RetryAfterAware) failure).retryAfter())
        .isEqualTo(Duration.ofSeconds(42));
  }

  @Test
  @DisplayName("Should journal a success and return the verified value when the verifier returns")
  void shouldJournalSuccessAndReturnVerifiedValueWhenVerifierReturns() {
    var verified = gate.attempt(LOGIN_TARGET, () -> "session");

    assertThat(verified).isEqualTo("session");
    assertThat(repository.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target()).isEqualTo(LOGIN_TARGET);
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
              assertThat(attempt.completedAt()).isEqualTo(NOW);
            });
  }

  @Test
  @DisplayName("Should journal a failure and rethrow when the verifier refuses the credential")
  void shouldJournalFailureAndRethrowWhenVerifierRefusesCredential() {
    assertThatThrownBy(
            () ->
                gate.attempt(
                    LOGIN_TARGET,
                    () -> {
                      throw new InvalidCredentialsException();
                    }))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(repository.attempts())
        .singleElement()
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .isEqualTo(CredentialAttemptResult.FAILED);
  }

  @Test
  @DisplayName("Should leave the reservation pending and warn when the verifier fails unexpectedly")
  void shouldLeaveReservationPendingAndWarnWhenVerifierFailsUnexpectedly() {
    try (var logs = LogCapture.forClass(CredentialAttemptGate.class)) {
      assertThatThrownBy(
              () ->
                  gate.attempt(
                      LOGIN_TARGET,
                      () -> {
                        throw new IllegalStateException("encoder misconfigured");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("encoder misconfigured");

      assertThat(repository.attempts())
          .singleElement()
          .satisfies(
              attempt -> {
                assertThat(attempt.result()).isNull();
                assertThat(attempt.completedAt()).isNull();
              });
      assertThat(logs.events())
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains(ACCOUNT_ID.toString())
                      && event.getThrowableProxy() != null);
    }
  }

  @Test
  @DisplayName("Should refuse without running the verifier when the target is blocked")
  void shouldRefuseWithoutRunningVerifierWhenTargetIsBlocked() {
    repository.rejectReservations(Duration.ofSeconds(42));
    var verifierRuns = new AtomicInteger();

    assertThatThrownBy(() -> gate.attempt(LOGIN_TARGET, verifierRuns::incrementAndGet))
        .isInstanceOf(TooManyLoginAttemptsException.class);

    assertThat(verifierRuns).hasValue(0);
    assertThat(repository.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should journal a success when a verifier without a result returns")
  void shouldJournalSuccessWhenVerifierWithoutResultReturns() {
    var verifierRuns = new AtomicInteger();

    gate.attempt(LOGIN_TARGET, (CredentialAttemptGate.Verification) verifierRuns::incrementAndGet);

    assertThat(verifierRuns).hasValue(1);
    assertThat(repository.attempts())
        .singleElement()
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .isEqualTo(CredentialAttemptResult.SUCCEEDED);
  }
}
