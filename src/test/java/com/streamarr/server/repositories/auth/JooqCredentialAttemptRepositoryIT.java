package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
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
@DisplayName("jOOQ Credential Attempt Repository Integration Tests")
class JooqCredentialAttemptRepositoryIT extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final String IP_ADDRESS = "192.0.2.16";
  private static final CredentialAttemptPolicy.Limited LIMITED_POLICY =
      new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

  @Autowired private CredentialAttemptRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void deleteAttempts() {
    jdbcTemplate.update("DELETE FROM credential_attempt WHERE host(ip_address) = ?", IP_ADDRESS);
  }

  @Test
  @DisplayName("Should begin a full lockout when the fifth failure completes")
  void shouldBeginAFullLockoutWhenTheFifthFailureCompletes() {
    var target = resolvedTarget();

    for (var failure = 0; failure < 5; failure++) {
      var reservation = reserve(target, NOW.plusSeconds(failure));
      repository.complete(reservation, CredentialAttemptResult.FAILED, NOW.plusSeconds(failure));
    }

    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isEqualTo(new CredentialAttemptAdmission.Blocked(Duration.ofMinutes(15).minusSeconds(1)));
    assertThat(attemptCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should reset the failure sequence after the latest successful verification")
  void shouldResetTheFailureSequenceAfterTheLatestSuccessfulVerification() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 4);
    var success = reserve(target, NOW.plusSeconds(4));
    repository.complete(success, CredentialAttemptResult.SUCCEEDED, NOW.plusSeconds(4));

    completeFailures(target, NOW.plusSeconds(5), 4);

    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plusSeconds(9)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should admit an attempt after the completed lockout expires")
  void shouldAdmitAnAttemptAfterTheCompletedLockoutExpires() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 5);

    assertThat(
            repository.reserve(
                target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(15)).plusSeconds(4)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should count fresh pending reservations and ignore abandoned reservations")
  void shouldCountFreshPendingReservationsAndIgnoreAbandonedReservations() {
    var target = resolvedTarget();
    for (var pending = 0; pending < 5; pending++) {
      reserve(target, NOW);
    }

    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(
            repository.reserve(
                target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(5)).plusNanos(1)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should record unlimited attempts without rejecting them")
  void shouldRecordUnlimitedAttemptsWithoutRejectingThem() {
    var target = resolvedTarget();
    var policy = new CredentialAttemptPolicy.Unlimited();

    for (var attempt = 0; attempt < 20; attempt++) {
      assertThat(repository.reserve(target, policy, NOW))
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
            .ipAddress(IP_ADDRESS)
            .build();

    for (var attempt = 0; attempt < 20; attempt++) {
      var reservation = reserve(target, NOW);
      repository.complete(reservation, CredentialAttemptResult.FAILED, NOW);
    }

    assertThat(attemptCount()).isEqualTo(20);
  }

  @Test
  @DisplayName("Should admit no more than five parallel reservations across service instances")
  void shouldAdmitNoMoreThanFiveParallelReservationsAcrossServiceInstances() throws Exception {
    var target = resolvedTarget();
    var admissions = new ArrayList<CredentialAttemptAdmission>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          IntStream.range(0, 20)
              .mapToObj(_ -> executor.submit(() -> repository.reserve(target, LIMITED_POLICY, NOW)))
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
    reserve(target, NOW.minus(Duration.ofDays(30)).minusSeconds(1));
    reserve(target, NOW.minus(Duration.ofDays(30)));

    assertThat(repository.deleteAttemptedBefore(NOW.minus(Duration.ofDays(30)))).isEqualTo(1);
    assertThat(attemptCount()).isEqualTo(1);
  }

  private static CredentialAttemptTarget resolvedTarget() {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.ACCOUNT_LOGIN)
        .accountId(UUID.randomUUID())
        .ipAddress(IP_ADDRESS)
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
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM credential_attempt WHERE host(ip_address) = ?",
        Integer.class,
        IP_ADDRESS);
  }
}
