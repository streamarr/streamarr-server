package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockProbe;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Account Administration Concurrency Integration Tests")
class AccountAdministrationConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private AccountAdministrationService accountAdministrationService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void deleteFixtures() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should finish an authorized transition before ServerAdmin revocation commits")
  void shouldFinishAuthorizedTransitionBeforeServerAdminRevocationCommits() throws Exception {
    var actor = identity(authTestSupport.createAdminIdentity());
    identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());
    var targetLocked = new CountDownLatch(1);
    var releaseTarget = new CountDownLatch(1);
    var revocationStarted = new CountDownLatch(1);
    var revocationBackendPid = new AtomicInteger();
    var targetLockerBackendPid = new AtomicInteger();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var targetLock =
          executor.submit(
              () ->
                  lockTarget(
                      target, targetLocked, releaseTarget, targetLockerBackendPid, lockProbe));
      assertThat(targetLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var grant =
          executor.submit(
              () ->
                  accountAdministrationService.grantServerAdmin(
                      authenticatedIdentity(actor), target.account().getId(), "new operator"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(hasWaitingUserAccountUpdates(targetLockerBackendPid.get(), 1))
                      .isTrue());

      var revocation =
          executor.submit(
              () -> {
                revokeServerAdmin(actor, revocationStarted, revocationBackendPid, lockProbe);
                return null;
              });
      assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(lockProbe.isUserAccountUpdateWaiting(revocationBackendPid.get()))
                      .isTrue());

      releaseTarget.countDown();
      assertThat(grant.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      targetLock.get(10, TimeUnit.SECONDS);
      revocation.get(10, TimeUnit.SECONDS);
    } finally {
      releaseTarget.countDown();
    }

    assertThat(userAccountRepository.findById(target.account().getId()).orElseThrow())
        .extracting(UserAccount::isServerAdmin)
        .isEqualTo(true);
    assertThat(userAccountRepository.findById(actor.account().getId()).orElseThrow())
        .extracting(UserAccount::isServerAdmin)
        .isEqualTo(false);
  }

  @Test
  @DisplayName("Should audit exactly one winner when identical transitions race")
  void shouldAuditExactlyOneWinnerWhenIdenticalTransitionsRace() throws Exception {
    var actor = identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());
    var targetLocked = new CountDownLatch(1);
    var releaseTarget = new CountDownLatch(1);
    var targetLockerBackendPid = new AtomicInteger();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var targetLock =
          executor.submit(
              () ->
                  lockTarget(
                      target, targetLocked, releaseTarget, targetLockerBackendPid, lockProbe));
      assertThat(targetLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var first =
          executor.submit(
              () ->
                  accountAdministrationService.grantServerAdmin(
                      authenticatedIdentity(actor), target.account().getId(), "first"));
      var second =
          executor.submit(
              () ->
                  accountAdministrationService.grantServerAdmin(
                      authenticatedIdentity(actor), target.account().getId(), "second"));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(hasWaitingUserAccountUpdates(targetLockerBackendPid.get(), 2))
                      .isTrue());

      releaseTarget.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      targetLock.get(10, TimeUnit.SECONDS);
    } finally {
      releaseTarget.countDown();
    }

    assertThat(userAccountRepository.findById(target.account().getId()).orElseThrow())
        .extracting(UserAccount::isServerAdmin)
        .isEqualTo(true);
    assertThat(
            dsl.selectFrom(SECURITY_AUDIT_EVENT)
                .where(SECURITY_AUDIT_EVENT.OPERATION.eq("grantServerAdmin"))
                .and(SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(actor.account().getId()))
                .fetch())
        .filteredOn(
            audit -> audit.getResources().data().contains(target.account().getId().toString()))
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getOperation()).isEqualTo("grantServerAdmin");
              assertThat(audit.getActorAccountId()).isEqualTo(actor.account().getId());
              assertThat(audit.getResources().data()).contains(target.account().getId().toString());
            });
  }

  private Void lockTarget(
      AuthTestSupport.TestIdentity target,
      CountDownLatch targetLocked,
      CountDownLatch releaseTarget,
      AtomicInteger targetLockerBackendPid,
      PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          targetLockerBackendPid.set(lockProbe.currentBackendPid());
          dsl.select(USER_ACCOUNT.ID)
              .from(USER_ACCOUNT)
              .where(USER_ACCOUNT.ID.eq(target.account().getId()))
              .forUpdate()
              .fetchSingle();
          targetLocked.countDown();
          awaitLatch(releaseTarget, "Account transition did not reach the target row");
        });
    return null;
  }

  private void revokeServerAdmin(
      AuthTestSupport.TestIdentity actor,
      CountDownLatch revocationStarted,
      AtomicInteger revocationBackendPid,
      PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(actor.account().getId()).orElseThrow();
          revocationBackendPid.set(lockProbe.currentBackendPid());
          revocationStarted.countDown();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private boolean hasWaitingUserAccountUpdates(int blockerBackendPid, int expectedCount) {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            WITH RECURSIVE blocked(pid) AS (
              SELECT activity.pid
              FROM pg_stat_activity activity
              WHERE ? = ANY(pg_blocking_pids(activity.pid))
              UNION
              SELECT activity.pid
              FROM pg_stat_activity activity
              JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
            )
            SELECT count(*)
            FROM pg_stat_activity activity
            JOIN blocked ON blocked.pid = activity.pid
            WHERE activity.wait_event_type = 'Lock'
              AND activity.query ILIKE '%update%user_account%'
            """,
            Integer.class, blockerBackendPid);
    return waiting != null && waiting >= expectedCount;
  }

  private AuthTestSupport.TestIdentity identity(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static AuthenticatedIdentity authenticatedIdentity(
      AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .serverAdmin(true)
        .contextHouseholdId(identity.household().getId())
        .reauthenticatedAt(Optional.of(Instant.now()))
        .build();
  }

  private static void awaitLatch(CountDownLatch latch, String failureMessage) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as(failureMessage).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating Account administration", e);
    }
  }
}
