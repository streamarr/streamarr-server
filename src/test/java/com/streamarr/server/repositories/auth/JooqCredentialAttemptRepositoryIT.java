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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("jOOQ Credential Attempt Repository Integration Tests")
@Import(JooqCredentialAttemptRepositoryIT.ClockConfiguration.class)
class JooqCredentialAttemptRepositoryIT extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final String IP_ADDRESS = "192.0.2.16";
  private static final CredentialAttemptPolicy LIMITED_POLICY =
      new StandardCredentialAttemptPolicyProvider().policyFor(CredentialKind.ACCOUNT_LOGIN);

  @Autowired private CredentialAttemptRepository repository;
  @Autowired private TestCredentialAttemptClock journalClock;
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
      completeAt(reservation, CredentialAttemptResult.FAILED, NOW.plusSeconds(failure));
    }

    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isEqualTo(new CredentialAttemptAdmission.Blocked(Duration.ofMinutes(15).minusSeconds(1)));
    assertThat(attemptCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should reset failure sequence when verification succeeds")
  void shouldResetFailureSequenceWhenVerificationSucceeds() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 4);
    var success = reserve(target, NOW.plusSeconds(4));
    completeAt(success, CredentialAttemptResult.SUCCEEDED, NOW.plusSeconds(4));

    completeFailures(target, NOW.plusSeconds(5), 4);

    // Four failures since the success still admit; only failures after the success count, so
    // the fifth of them is the one that begins the lockout.
    var fifthSinceSuccess = reserve(target, NOW.plusSeconds(9));
    completeAt(fifthSinceSuccess, CredentialAttemptResult.FAILED, NOW.plusSeconds(9));
    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(10)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
  }

  @Test
  @DisplayName("Should admit attempt when completed lockout expires")
  void shouldAdmitAttemptWhenCompletedLockoutExpires() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 5);

    assertThat(admit(target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(15)).plusSeconds(4)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should admit an attempt exactly when the failure window closes")
  void shouldAdmitAnAttemptExactlyWhenTheFailureWindowCloses() {
    var target = resolvedTarget();
    for (var failure = 0; failure < 5; failure++) {
      completeAt(reserve(target, NOW), CredentialAttemptResult.FAILED, NOW);
    }

    // The lockout and the window both end here; a client retrying at Retry-After is admitted.
    assertThat(admit(target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(15))))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should hold a slot for a pending reservation when it is fresh")
  void shouldHoldSlotForPendingReservationWhenItIsFresh() {
    var target = resolvedTarget();
    for (var pending = 0; pending < 5; pending++) {
      reserve(target, NOW);
    }

    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(admit(target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(5)).minusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
  }

  @Test
  @DisplayName("Should free the slot when a pending reservation is five minutes old")
  void shouldFreeSlotWhenPendingReservationIsFiveMinutesOld() {
    var target = resolvedTarget();
    for (var pending = 0; pending < 5; pending++) {
      reserve(target, NOW);
    }

    assertThat(admit(target, LIMITED_POLICY, NOW.plus(Duration.ofMinutes(5))))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should count pending reservations when later attempt succeeds")
  void shouldCountPendingReservationsWhenLaterAttemptSucceeds() {
    var target = resolvedTarget();
    for (var pending = 0; pending < 4; pending++) {
      reserve(target, NOW);
    }

    var success = reserve(target, NOW.plusSeconds(1));
    completeAt(success, CredentialAttemptResult.SUCCEEDED, NOW.plusSeconds(1));

    // The four in-flight verifications may still fail after the success; they hold their slots.
    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(2)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(2)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
  }

  @Test
  @DisplayName("Should not lock out when five failures span more than the window")
  void shouldNotLockOutWhenFiveFailuresSpanMoreThanTheWindow() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 4);
    var late = NOW.plus(Duration.ofMinutes(16));
    completeAt(reserve(target, late), CredentialAttemptResult.FAILED, late);

    assertThat(admit(target, LIMITED_POLICY, late.plusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName(
      "Should measure the lockout from the fifth failure's completion when completion lags its reservation")
  void shouldMeasureLockoutFromFifthFailuresCompletionWhenCompletionLagsReservation() {
    var target = resolvedTarget();
    var reservations = IntStream.range(0, 5).mapToObj(_ -> reserve(target, NOW)).toList();
    var completedAt = NOW.plusSeconds(30);
    reservations.forEach(
        reservation -> completeAt(reservation, CredentialAttemptResult.FAILED, completedAt));
    var lockoutEnd = completedAt.plus(Duration.ofMinutes(15));

    assertThat(admit(target, LIMITED_POLICY, lockoutEnd.minusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(admit(target, LIMITED_POLICY, lockoutEnd))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should keep PIN failure sequences separate when Profiles share an Account")
  void shouldKeepPinFailureSequencesSeparateWhenProfilesShareAnAccount() {
    var accountId = UUID.randomUUID();
    var firstProfile = pinTarget(accountId, UUID.randomUUID());
    var secondProfile = pinTarget(accountId, UUID.randomUUID());
    completeFailures(firstProfile, NOW, 5);

    assertThat(admit(firstProfile, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
    assertThat(admit(secondProfile, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should keep a Profile locked when another Profile's PIN verification succeeds")
  void shouldKeepProfileLockedWhenAnotherProfilesPinVerificationSucceeds() {
    var accountId = UUID.randomUUID();
    var lockedProfile = pinTarget(accountId, UUID.randomUUID());
    var siblingProfile = pinTarget(accountId, UUID.randomUUID());
    completeFailures(lockedProfile, NOW, 5);

    var siblingSuccess = reserve(siblingProfile, NOW.plusSeconds(6));
    completeAt(siblingSuccess, CredentialAttemptResult.SUCCEEDED, NOW.plusSeconds(6));

    assertThat(admit(lockedProfile, LIMITED_POLICY, NOW.plusSeconds(7)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
  }

  @Test
  @DisplayName("Should keep login failures out of password verification when they share an Account")
  void shouldKeepLoginFailuresOutOfPasswordVerificationWhenTheyShareAnAccount() {
    var accountId = UUID.randomUUID();
    completeFailures(loginTarget(accountId), NOW, 5);
    var passwordVerification =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
            .accountId(accountId)
            .ipAddress(IP_ADDRESS)
            .build();

    assertThat(admit(passwordVerification, LIMITED_POLICY, NOW.plusSeconds(8)))
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
  @DisplayName("Should record every attempt without rejecting when the policy is unlimited")
  void shouldRecordEveryAttemptWithoutRejectingWhenPolicyIsUnlimited() {
    var target = resolvedTarget();
    var policy = new CredentialAttemptPolicy.Unlimited();

    for (var attempt = 0; attempt < 20; attempt++) {
      assertThat(admit(target, policy, NOW))
          .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
    }

    assertThat(attemptCount()).isEqualTo(20);
  }

  @Test
  @DisplayName("Should record every attempt without throttling when the target is unresolved")
  void shouldRecordEveryAttemptWithoutThrottlingWhenTargetIsUnresolved() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PASSWORD_RESET_CODE)
            .ipAddress(IP_ADDRESS)
            .build();

    for (var attempt = 0; attempt < 20; attempt++) {
      var reservation = reserve(target, NOW);
      completeAt(reservation, CredentialAttemptResult.FAILED, NOW);
    }

    assertThat(attemptCount()).isEqualTo(20);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CredentialKind.class)
  @DisplayName(
      "Should serve every admission query from the target indexes when sequential scans are disabled")
  void shouldServeEveryAdmissionQueryFromTheTargetIndexesWhenSequentialScansAreDisabled(
      CredentialKind kind) {
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
    var builder = CredentialAttemptTarget.builder().kind(kind).ipAddress(IP_ADDRESS);
    var indexColumn =
        switch (kind) {
          case ACCOUNT_LOGIN, ACCOUNT_PASSWORD_VERIFICATION, DEVICE_PAIRING_CODE -> {
            builder.accountId(UUID.randomUUID());
            yield "account_id";
          }

          case PROFILE_PIN -> {
            builder.accountId(UUID.randomUUID()).profileId(UUID.randomUUID());
            yield "profile_id";
          }

          case ACCOUNT_INVITATION_CODE, PASSWORD_RESET_CODE, PROFILE_MANAGER_INVITATION_CODE -> {
            builder.credentialId(UUID.randomUUID());
            yield "credential_id";
          }
        };

    var target = builder.build();
    var policy = new StandardCredentialAttemptPolicyProvider().policyFor(kind);
    transactionTemplate.executeWithoutResult(
        _ ->
            new JooqCredentialAttemptRepository(recording, transactionLocks, journalClock)
                .reserve(target, policy));

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
                .containsPattern("Index Cond: \\(.*" + indexColumn + " = ");
          }
        });
  }

  @Test
  @DisplayName("Should refuse completion when reservation is no longer pending")
  void shouldRefuseCompletionWhenReservationIsNoLongerPending() {
    var target = resolvedTarget();
    var reservation = reserve(target, NOW);
    completeAt(reservation, CredentialAttemptResult.FAILED, NOW);

    assertThatThrownBy(() -> completeAt(reservation, CredentialAttemptResult.SUCCEEDED, NOW))
        .isInstanceOf(CredentialAttemptNotPendingException.class);
  }

  @Test
  @DisplayName("Should complete at the reservation instant when the clock stepped backwards")
  void shouldCompleteAtTheReservationInstantWhenTheClockSteppedBackwards() {
    var target = resolvedTarget();
    var reservation = reserve(target, NOW);

    completeAt(reservation, CredentialAttemptResult.FAILED, NOW.minusSeconds(1));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT completed_at = attempted_at FROM credential_attempt WHERE id = ?",
                Boolean.class,
                reservation.id()))
        .isTrue();
  }

  @ParameterizedTest(name = "outcome={0}")
  @ValueSource(strings = {"PENDING", "SUCCEEDED", "FAILED"})
  @DisplayName("Should remove only older attempts when the cutoff is thirty days")
  void shouldRemoveOnlyOlderAttemptsWhenCutoffIsThirtyDays(String outcome) {
    var target = resolvedTarget();
    var older = reserve(target, NOW.minus(Duration.ofDays(30)).minusSeconds(1));
    var boundary = reserve(target, NOW.minus(Duration.ofDays(30)));
    if (!outcome.equals("PENDING")) {
      completeAt(
          older,
          CredentialAttemptResult.valueOf(outcome),
          NOW.minus(Duration.ofDays(30)).minusSeconds(1));
      completeAt(
          boundary, CredentialAttemptResult.valueOf(outcome), NOW.minus(Duration.ofDays(30)));
    }

    assertThat(repository.deleteAttemptedBefore(NOW.minus(Duration.ofDays(30)))).isEqualTo(1);
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT attempted_at FROM credential_attempt WHERE host(ip_address) = ?",
                    OffsetDateTime.class,
                    IP_ADDRESS)
                .toInstant())
        .isEqualTo(NOW.minus(Duration.ofDays(30)));
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

  private CredentialAttemptAdmission admit(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
    journalClock.set(attemptedAt);
    return repository.reserve(target, policy);
  }

  private void completeAt(
      CredentialAttemptReservation reservation,
      CredentialAttemptResult result,
      Instant completedAt) {
    journalClock.set(completedAt);
    repository.complete(reservation, result);
  }

  private CredentialAttemptReservation reserve(
      CredentialAttemptTarget target, Instant attemptedAt) {
    return switch (admit(target, LIMITED_POLICY, attemptedAt)) {
      case CredentialAttemptAdmission.Reserved(var reservation) -> reservation;
      case CredentialAttemptAdmission.Blocked _ -> throw new AssertionError("attempt was blocked");
    };
  }

  private void completeFailures(
      CredentialAttemptTarget target, Instant firstAttempt, int numberOfFailures) {
    for (var failure = 0; failure < numberOfFailures; failure++) {
      var completedAt = firstAttempt.plusSeconds(failure);
      completeAt(reserve(target, completedAt), CredentialAttemptResult.FAILED, completedAt);
    }
  }

  private int attemptCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM credential_attempt WHERE host(ip_address) = ?",
        Integer.class,
        IP_ADDRESS);
  }

  @Test
  @DisplayName("Should block the target when the same Account fails from another address")
  void shouldBlockTargetWhenSameAccountFailsFromAnotherAddress() {
    var accountId = UUID.randomUUID();
    completeFailures(loginTarget(accountId), NOW, 5);
    var fromElsewhere =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(accountId)
            .ipAddress("198.51.100.9")
            .build();

    // The address is observational (ADR 0028): it is never part of the throttle key.
    assertThat(admit(fromElsewhere, LIMITED_POLICY, NOW.plusSeconds(5)))
        .isInstanceOf(CredentialAttemptAdmission.Blocked.class);
  }

  @Test
  @DisplayName("Should ignore failure when it completes at latest success instant")
  void shouldIgnoreFailureWhenItCompletesAtLatestSuccessInstant() {
    var target = resolvedTarget();
    var success = reserve(target, NOW.minusSeconds(1));
    completeAt(success, CredentialAttemptResult.SUCCEEDED, NOW);
    for (var failure = 0; failure < 5; failure++) {
      completeAt(reserve(target, NOW), CredentialAttemptResult.FAILED, NOW);
    }

    // The success cutoff also excludes failures completed at the same instant.
    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(1)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should keep the completion when the caller's transaction rolls back")
  void shouldKeepTheCompletionWhenTheCallersTransactionRollsBack() {
    var target = resolvedTarget();
    var reservation = reserve(target, NOW);

    transactionTemplate.executeWithoutResult(
        status -> {
          completeAt(reservation, CredentialAttemptResult.FAILED, NOW);
          status.setRollbackOnly();
        });

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result::text FROM credential_attempt WHERE id = ?",
                String.class,
                reservation.id()))
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Should refuse a result without a completion instant when the row is written")
  void shouldRefuseResultWithoutCompletionInstantWhenRowIsWritten() {
    var reservation = reserve(resolvedTarget(), NOW);
    var reservationId = reservation.id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE credential_attempt SET result = 'FAILED' WHERE id = ?", reservationId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "UPDATE credential_attempt SET completed_at = attempted_at WHERE id = ?",
        "UPDATE credential_attempt SET result = 'FAILED', completed_at = attempted_at - interval '1 second' WHERE id = ?"
      })
  @DisplayName("Should reject invalid completion state when another writer updates the journal")
  void shouldRejectInvalidCompletionStateWhenAnotherWriterUpdatesJournal(String update) {
    var reservation = reserve(resolvedTarget(), NOW);
    var reservationId = reservation.id();
    assertThatThrownBy(() -> jdbcTemplate.update(update, reservationId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should preserve the original retry boundary when blocked requests repeat")
  void shouldPreserveOriginalRetryBoundaryWhenBlockedRequestsRepeat() {
    var target = resolvedTarget();
    completeFailures(target, NOW, 5);
    for (var seconds : new int[] {30, 60, 899}) {
      assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(seconds)))
          .isEqualTo(new CredentialAttemptAdmission.Blocked(Duration.ofSeconds(904 - seconds)));
      assertThat(attemptCount()).isEqualTo(5);
    }

    assertThat(admit(target, LIMITED_POLICY, NOW.plusSeconds(904)))
        .isInstanceOf(CredentialAttemptAdmission.Reserved.class);
  }

  @Test
  @DisplayName("Should store only journal metadata when the journal schema is inspected")
  void shouldStoreOnlyJournalMetadataWhenJournalSchemaIsInspected() {
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'credential_attempt'",
                String.class))
        .containsExactlyInAnyOrder(
            "id",
            "credential_kind",
            "account_id",
            "profile_id",
            "credential_id",
            "ip_address",
            "attempted_at",
            "completed_at",
            "result");
  }

  @Test
  @DisplayName("Should keep the address unindexed when the journal is created")
  void shouldKeepTheAddressUnindexedWhenTheJournalIsCreated() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes"
                    + " WHERE tablename = 'credential_attempt' AND indexdef ILIKE '%ip_address%'",
                Integer.class))
        .isZero();
  }

  @TestConfiguration
  static class ClockConfiguration {

    @Bean
    @Primary
    TestCredentialAttemptClock journalClock() {
      return new TestCredentialAttemptClock();
    }
  }

  static class TestCredentialAttemptClock implements CredentialAttemptClock {

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    @Override
    public Instant instant() {
      return now.get();
    }

    void set(Instant instant) {
      now.set(instant);
    }
  }
}
