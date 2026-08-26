package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.support.LogCapture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
      assertThatThrownBy(() -> gate.reserve(LOGIN_TARGET)).isInstanceOf(RuntimeException.class);

      assertThat(logs.events())
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("ACCOUNT_LOGIN")
                      && event.getFormattedMessage().contains(ACCOUNT_ID.toString())
                      && event.getFormattedMessage().contains("PT42S"));
    }
  }
}
