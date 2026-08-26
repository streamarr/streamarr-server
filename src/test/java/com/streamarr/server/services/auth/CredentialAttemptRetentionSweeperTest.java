package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Attempt Retention Sweeper Tests")
class CredentialAttemptRetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

  @Test
  @DisplayName("Should delete only attempts older than thirty days")
  void shouldDeleteOnlyAttemptsOlderThanThirtyDays() {
    var repository = new FakeCredentialAttemptRepository();
    reserveAt(repository, NOW.minus(CredentialAttemptRetentionSweeper.RETENTION).minusSeconds(1));
    reserveAt(repository, NOW.minus(CredentialAttemptRetentionSweeper.RETENTION));
    reserveAt(repository, NOW.minusSeconds(1));
    var sweeper =
        new CredentialAttemptRetentionSweeper(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    sweeper.deleteExpiredAttempts();

    assertThat(repository.attempts())
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::attemptedAt)
        .containsExactly(
            NOW.minus(CredentialAttemptRetentionSweeper.RETENTION), NOW.minusSeconds(1));
  }

  private static void reserveAt(FakeCredentialAttemptRepository repository, Instant attemptedAt) {
    var gate = repository.gate(Clock.fixed(attemptedAt, ZoneOffset.UTC));
    var reservation =
        gate.reserve(
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_LOGIN)
                .ipAddress("192.0.2.30")
                .build());
    gate.complete(reservation, CredentialAttemptResult.FAILED);
  }
}
