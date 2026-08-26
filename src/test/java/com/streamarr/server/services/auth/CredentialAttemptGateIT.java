package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Credential Attempt Gate Integration Tests")
class CredentialAttemptGateIT extends AbstractIntegrationTest {

  @Autowired private CredentialAttemptGate gate;
  @Autowired private CredentialAttemptRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final CredentialAttemptPolicy.Limited LIMITED_POLICY =
      new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

  @AfterEach
  void deleteAttempts() {
    jdbcTemplate.update("DELETE FROM credential_attempt");
  }

  @Test
  @DisplayName("Should persist a failed login attempt with its Account and IP address")
  void shouldPersistAFailedLoginAttemptWithItsAccountAndIpAddress() {
    var before = Instant.now();
    var accountId = UUID.randomUUID();
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(accountId)
            .ipAddress("192.0.2.15")
            .build();

    var reservation = gate.reserve(target);
    gate.complete(reservation, CredentialAttemptResult.FAILED);

    var row =
        jdbcTemplate.queryForMap(
            """
            SELECT id, credential_kind::text AS credential_kind, account_id, profile_id,
                   credential_id, host(ip_address) AS ip_address, attempted_at, completed_at,
                   result::text AS result
              FROM credential_attempt
            """);
    assertThat(row.get("id")).isEqualTo(reservation.id());
    assertThat(row.get("credential_kind")).isEqualTo("ACCOUNT_LOGIN");
    assertThat(row.get("account_id")).isEqualTo(accountId);
    assertThat(row.get("profile_id")).isNull();
    assertThat(row.get("credential_id")).isNull();
    assertThat(row.get("ip_address")).isEqualTo("192.0.2.15");
    assertThat(((Timestamp) row.get("attempted_at")).toInstant()).isAfterOrEqualTo(before);
    assertThat(((Timestamp) row.get("completed_at")).toInstant())
        .isAfterOrEqualTo(((Timestamp) row.get("attempted_at")).toInstant());
    assertThat(row.get("result")).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Should begin a full lockout when the fifth failure completes")
  void shouldBeginAFullLockoutWhenTheFifthFailureCompletes() {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");

    for (var failure = 0; failure < 5; failure++) {
      var reservation = reserve(target, now.plusSeconds(failure));
      repository.complete(reservation, CredentialAttemptResult.FAILED, now.plusSeconds(failure));
    }

    assertThat(repository.reserve(target, LIMITED_POLICY, now.plusSeconds(5)))
        .isEqualTo(new CredentialAttemptAdmission.Blocked(Duration.ofMinutes(15).minusSeconds(1)));
    assertThat(attemptCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should reset the failure sequence after the latest successful verification")
  void shouldResetTheFailureSequenceAfterTheLatestSuccessfulVerification() {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");
    completeFailures(target, now, 4);
    var success = reserve(target, now.plusSeconds(4));
    repository.complete(success, CredentialAttemptResult.SUCCEEDED, now.plusSeconds(4));

    completeFailures(target, now.plusSeconds(5), 4);

    assertThat(repository.reserve(target, LIMITED_POLICY, now.plusSeconds(9)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should admit an attempt after the completed lockout expires")
  void shouldAdmitAnAttemptAfterTheCompletedLockoutExpires() {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");
    completeFailures(target, now, 5);

    assertThat(
            repository.reserve(
                target, LIMITED_POLICY, now.plus(Duration.ofMinutes(15)).plusSeconds(4)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should count fresh pending reservations and ignore abandoned reservations")
  void shouldCountFreshPendingReservationsAndIgnoreAbandonedReservations() {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");
    for (var pending = 0; pending < 5; pending++) {
      reserve(target, now);
    }

    assertThat(repository.reserve(target, LIMITED_POLICY, now.plusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(
            repository.reserve(
                target, LIMITED_POLICY, now.plus(Duration.ofMinutes(5)).plusNanos(1)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should record unlimited attempts without rejecting them")
  void shouldRecordUnlimitedAttemptsWithoutRejectingThem() {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");
    var policy = new CredentialAttemptPolicy.Unlimited();

    for (var attempt = 0; attempt < 20; attempt++) {
      assertThat(repository.reserve(target, policy, now))
          .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
    }

    assertThat(attemptCount()).isEqualTo(20);
  }

  @Test
  @DisplayName("Should record unresolved credentials without subject throttling")
  void shouldRecordUnresolvedCredentialsWithoutSubjectThrottling() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PASSWORD_RESET_CODE)
            .ipAddress("192.0.2.16")
            .build();
    var now = Instant.parse("2026-08-26T12:00:00Z");

    for (var attempt = 0; attempt < 20; attempt++) {
      var reservation = reserve(target, now);
      repository.complete(reservation, CredentialAttemptResult.FAILED, now);
    }

    assertThat(attemptCount()).isEqualTo(20);
  }

  @Test
  @DisplayName("Should admit no more than five parallel reservations across service instances")
  void shouldAdmitNoMoreThanFiveParallelReservationsAcrossServiceInstances() throws Exception {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");
    var admissions = new ArrayList<CredentialAttemptAdmission>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          IntStream.range(0, 20)
              .mapToObj(_ -> executor.submit(() -> repository.reserve(target, LIMITED_POLICY, now)))
              .toList();
      for (var task : tasks) {
        admissions.add(task.get());
      }
    }

    assertThat(admissions)
        .filteredOn(CredentialAttemptAdmission.Reserved.class::isInstance)
        .hasSize(5);
    assertThat(admissions)
        .filteredOn(CredentialAttemptAdmission.Blocked.class::isInstance)
        .hasSize(15);
    assertThat(attemptCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should remove only attempts older than thirty days")
  void shouldRemoveOnlyAttemptsOlderThanThirtyDays() {
    var target = resolvedTarget();
    var now = Instant.parse("2026-08-26T12:00:00Z");
    reserve(target, now.minus(Duration.ofDays(30)).minusSeconds(1));
    reserve(target, now.minus(Duration.ofDays(30)));

    assertThat(repository.deleteAttemptedBefore(now.minus(Duration.ofDays(30)))).isEqualTo(1);
    assertThat(attemptCount()).isEqualTo(1);
  }

  private CredentialAttemptTarget resolvedTarget() {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.ACCOUNT_LOGIN)
        .accountId(UUID.randomUUID())
        .ipAddress("192.0.2.15")
        .build();
  }

  private CredentialAttemptReservation reserve(
      CredentialAttemptTarget target, Instant attemptedAt) {
    return switch (repository.reserve(target, LIMITED_POLICY, attemptedAt)) {
      case CredentialAttemptAdmission.Reserved(var reservation) -> reservation;
      case CredentialAttemptAdmission.Blocked _ -> throw new AssertionError("attempt was blocked");
    };
  }

  private void completeFailures(
      CredentialAttemptTarget target, Instant firstAttempt, int numberOfFailures) {
    for (var failure = 0; failure < numberOfFailures; failure++) {
      var completedAt = firstAttempt.plusSeconds(failure);
      repository.complete(
          reserve(target, completedAt), CredentialAttemptResult.FAILED, completedAt);
    }
  }

  private int attemptCount() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM credential_attempt", Integer.class);
  }
}
