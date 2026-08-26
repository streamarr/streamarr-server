package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.support.LogCapture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Attempt Retention Sweeper Tests")
class CredentialAttemptRetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  // ADR 0028: attempts are retained for 30 days.
  private static final Duration RETENTION = Duration.ofDays(30);

  private final FakeCredentialAttemptRepository repository = new FakeCredentialAttemptRepository();
  private final CredentialAttemptRetentionSweeper sweeper =
      new CredentialAttemptRetentionSweeper(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("Should delete only older attempts when retention is thirty days")
  void shouldDeleteOnlyOlderAttemptsWhenRetentionIsThirtyDays() {
    reserveAt(NOW.minus(RETENTION).minusSeconds(1));
    reserveAt(NOW.minus(RETENTION));
    reserveAt(NOW.minusSeconds(1));

    sweeper.deleteExpiredAttempts();

    assertThat(repository.attempts())
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::attemptedAt)
        .containsExactly(NOW.minus(RETENTION), NOW.minusSeconds(1));
  }

  @Test
  @DisplayName("Should log the deleted count and cutoff when the sweep runs")
  void shouldLogDeletedCountAndCutoffWhenSweepRuns() {
    reserveAt(NOW.minus(RETENTION).minusSeconds(2));
    reserveAt(NOW.minus(RETENTION).minusSeconds(1));

    try (var logs = LogCapture.forClass(CredentialAttemptRetentionSweeper.class)) {
      sweeper.deleteExpiredAttempts();

      assertThat(logs.events())
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                    .isEqualTo(
                        "Deleted 2 credential attempts attempted before 2026-07-27T12:00:00Z");
              });
    }
  }

  private void reserveAt(Instant attemptedAt) {
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
