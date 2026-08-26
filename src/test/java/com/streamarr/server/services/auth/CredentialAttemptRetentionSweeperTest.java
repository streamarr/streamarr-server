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
import org.springframework.scheduling.annotation.Scheduled;

@Tag("UnitTest")
@DisplayName("Credential Attempt Retention Sweeper Tests")
class CredentialAttemptRetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

  private final FakeCredentialAttemptRepository repository = new FakeCredentialAttemptRepository();
  private final CredentialAttemptRetentionSweeper sweeper =
      new CredentialAttemptRetentionSweeper(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("Should delete only attempts older than thirty days")
  void shouldDeleteOnlyAttemptsOlderThanThirtyDays() {
    reserveAt(NOW.minus(CredentialAttemptRetentionSweeper.RETENTION).minusSeconds(1));
    reserveAt(NOW.minus(CredentialAttemptRetentionSweeper.RETENTION));
    reserveAt(NOW.minusSeconds(1));

    sweeper.deleteExpiredAttempts();

    assertThat(repository.attempts())
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::attemptedAt)
        .containsExactly(
            NOW.minus(CredentialAttemptRetentionSweeper.RETENTION), NOW.minusSeconds(1));
  }

  @Test
  @DisplayName("Should log how many attempts the sweep deleted")
  void shouldLogHowManyAttemptsTheSweepDeleted() {
    reserveAt(NOW.minus(CredentialAttemptRetentionSweeper.RETENTION).minusSeconds(2));
    reserveAt(NOW.minus(CredentialAttemptRetentionSweeper.RETENTION).minusSeconds(1));

    try (var logs = LogCapture.forClass(CredentialAttemptRetentionSweeper.class)) {
      sweeper.deleteExpiredAttempts();

      assertThat(logs.events())
          .anyMatch(
              event ->
                  event.getLevel() == Level.INFO
                      && event.getFormattedMessage().contains("2 credential attempt"));
    }
  }

  @Test
  @DisplayName("Should sweep soon after startup and daily thereafter")
  void shouldSweepSoonAfterStartupAndDailyThereafter() throws NoSuchMethodException {
    var schedule =
        CredentialAttemptRetentionSweeper.class
            .getMethod("deleteExpiredAttempts")
            .getAnnotation(Scheduled.class);

    // An instance restarted more often than the initial delay would otherwise never sweep.
    assertThat(Duration.parse(schedule.initialDelayString()))
        .isLessThanOrEqualTo(Duration.ofMinutes(5));
    assertThat(Duration.parse(schedule.fixedDelayString())).isEqualTo(Duration.ofDays(1));
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
