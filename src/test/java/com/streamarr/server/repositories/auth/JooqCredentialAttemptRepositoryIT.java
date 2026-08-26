package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.CredentialAttemptNotPendingException;
import com.streamarr.server.services.auth.StandardCredentialAttemptPolicyProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("jOOQ Credential Attempt Repository Integration Tests")
class JooqCredentialAttemptRepositoryIT extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final String IP_ADDRESS = "192.0.2.16";
  private static final CredentialAttemptPolicy LIMITED_POLICY =
      new StandardCredentialAttemptPolicyProvider().policyFor(CredentialKind.ACCOUNT_LOGIN);

  @Autowired private CredentialAttemptRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private PostgresTransactionLocks transactionLocks;
  @Autowired private TransactionTemplate transactionTemplate;

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
  @DisplayName("Should admit an attempt exactly when the failure window closes")
  void shouldAdmitAnAttemptExactlyWhenTheFailureWindowCloses() {
    var target = resolvedTarget();
    for (var failure = 0; failure < 5; failure++) {
      repository.complete(reserve(target, NOW), CredentialAttemptResult.FAILED, NOW);
    }

    // The lockout and the window both end here; a client retrying at Retry-After is admitted.
    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(15))))
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
                target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(5)).minusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(5))))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should keep counting pending reservations made before the latest success")
  void shouldKeepCountingPendingReservationsMadeBeforeTheLatestSuccess() {
    var target = resolvedTarget();
    for (var pending = 0; pending < 4; pending++) {
      reserve(target, NOW);
    }
    var success = reserve(target, NOW.plusSeconds(1));
    repository.complete(success, CredentialAttemptResult.SUCCEEDED, NOW.plusSeconds(1));

    // The four in-flight verifications may still fail after the success; they hold their slots.
    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plusSeconds(2)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
    assertThat(repository.reserve(target, LIMITED_POLICY, NOW.plusSeconds(2)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
  }

  @Test
  @DisplayName("Should not lock out when five failures span more than the window")
  void shouldNotLockOutWhenFiveFailuresSpanMoreThanTheWindow() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 4);
    var late = NOW.plus(Duration.ofMinutes(16));
    repository.complete(reserve(target, late), CredentialAttemptResult.FAILED, late);

    assertThat(repository.reserve(target, LIMITED_POLICY, late.plusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName(
      "Should measure the lockout from the fifth failure's completion, not its reservation")
  void shouldMeasureLockoutFromFifthFailuresCompletionNotItsReservation() {
    var target = resolvedTarget();
    var reservations = IntStream.range(0, 5).mapToObj(_ -> reserve(target, NOW)).toList();
    var completedAt = NOW.plusSeconds(30);
    reservations.forEach(
        reservation ->
            repository.complete(reservation, CredentialAttemptResult.FAILED, completedAt));
    var lockoutEnd = completedAt.plus(Duration.ofMinutes(15));

    assertThat(repository.reserve(target, LIMITED_POLICY, lockoutEnd.minusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(repository.reserve(target, LIMITED_POLICY, lockoutEnd))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should keep every target's failure sequence separate")
  void shouldKeepEveryTargetsFailureSequenceSeparate() {
    var accountId = UUID.randomUUID();
    var firstProfile = pinTarget(accountId, UUID.randomUUID());
    var secondProfile = pinTarget(accountId, UUID.randomUUID());
    completeFailures(firstProfile, NOW, 5);

    assertThat(repository.reserve(firstProfile, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(repository.reserve(secondProfile, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);

    // A success on the sibling Profile forgives nothing for the locked one.
    var siblingSuccess = reserve(secondProfile, NOW.plusSeconds(6));
    repository.complete(siblingSuccess, CredentialAttemptResult.SUCCEEDED, NOW.plusSeconds(6));
    assertThat(repository.reserve(firstProfile, LIMITED_POLICY, NOW.plusSeconds(7)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);

    // Nor do the Account's login failures spill into its password verification.
    completeFailures(loginTarget(accountId), NOW, 5);
    var passwordVerification =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
            .accountId(accountId)
            .ipAddress(IP_ADDRESS)
            .build();
    assertThat(repository.reserve(passwordVerification, LIMITED_POLICY, NOW.plusSeconds(8)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should keep the journal row when the caller's transaction rolls back")
  void shouldKeepTheJournalRowWhenTheCallersTransactionRollsBack() {
    var target = resolvedTarget();

    transactionTemplate.executeWithoutResult(
        status -> {
          reserve(target, NOW);
          status.setRollbackOnly();
        });

    assertThat(attemptCount()).isEqualTo(1);
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
  @DisplayName("Should serve every admission query from the target indexes")
  void shouldServeEveryAdmissionQueryFromTheTargetIndexes() {
    var statements = new ArrayList<String>();
    var recordingListener =
        new ExecuteListener() {
          @Override
          public void executeStart(ExecuteContext context) {
            if (context.query() != null) {
              statements.add(context.dsl().renderInlined(context.query()));
            }
          }
        };
    var recording =
        DSL.using(
            dsl.configuration().derive(new DefaultExecuteListenerProvider(recordingListener)));
    var target = resolvedTarget();
    transactionTemplate.executeWithoutResult(
        _ ->
            new JooqCredentialAttemptRepository(recording, transactionLocks)
                .reserve(target, LIMITED_POLICY, NOW));

    var admissionQueries =
        statements.stream()
            .filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("select"))
            .filter(sql -> sql.contains("credential_attempt"))
            .toList();
    assertThat(admissionQueries).isNotEmpty();
    transactionTemplate.executeWithoutResult(
        _ -> {
          // A tiny table would otherwise be scanned on cost alone; the index must be usable.
          jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
          for (var sql : admissionQueries) {
            var plan = String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
            assertThat(plan)
                .as(sql)
                .doesNotContain("Seq Scan")
                .containsPattern("Index Cond: \\(.*account_id = ");
          }
        });
  }

  @Test
  @DisplayName("Should refuse to complete a reservation that is no longer pending")
  void shouldRefuseToCompleteAReservationThatIsNoLongerPending() {
    var target = resolvedTarget();
    var reservation = reserve(target, NOW);
    repository.complete(reservation, CredentialAttemptResult.FAILED, NOW);

    assertThatThrownBy(
            () -> repository.complete(reservation, CredentialAttemptResult.SUCCEEDED, NOW))
        .isInstanceOf(CredentialAttemptNotPendingException.class);
  }

  @Test
  @DisplayName("Should complete at the reservation instant when the clock stepped backwards")
  void shouldCompleteAtTheReservationInstantWhenTheClockSteppedBackwards() {
    var target = resolvedTarget();
    var reservation = reserve(target, NOW);

    repository.complete(reservation, CredentialAttemptResult.FAILED, NOW.minusSeconds(1));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT completed_at = attempted_at FROM credential_attempt WHERE id = ?",
                Boolean.class,
                reservation.id()))
        .isTrue();
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
    return loginTarget(UUID.randomUUID());
  }

  private static CredentialAttemptTarget loginTarget(UUID accountId) {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.ACCOUNT_LOGIN)
        .accountId(accountId)
        .ipAddress(IP_ADDRESS)
        .build();
  }

  private static CredentialAttemptTarget pinTarget(UUID accountId, UUID profileId) {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.PROFILE_PIN)
        .accountId(accountId)
        .profileId(profileId)
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
