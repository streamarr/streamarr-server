package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.CounterKind;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.CounterNotificationPayload;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import com.streamarr.server.services.auth.TokenVersionCache;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two-cache convergence proof: a counter bumped through one instance's repository must
 * invalidate tokens on a second instance whose cache holds the stale value — via LISTEN/NOTIFY, not
 * read-through.
 */
@Tag("IntegrationTest")
@DisplayName("Session Invalidation Integration Tests")
class SessionInvalidationIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private AuthSessionRepository sessionRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Autowired private VersionCounterReader versionCounterReader;

  @Autowired private TokenVersionCache localVersionCache;

  @Autowired private CounterNotificationConnectionSource connectionSource;

  @Autowired private CounterNotificationBackoff backoff;

  @Autowired private DSLContext dsl;

  @Autowired private TransactionTemplate transactionTemplate;

  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity sentinelIdentity;
  private CounterNotificationListener secondInstanceListener;

  @AfterEach
  void tearDown() {
    if (secondInstanceListener != null) {
      secondInstanceListener.stop();
    }
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
    if (sentinelIdentity != null) {
      authTestSupport.deleteIdentity(sentinelIdentity);
    }
  }

  @Test
  @DisplayName("Should reject token on second instance when counter bumped on first")
  void shouldRejectTokenOnSecondInstanceWhenCounterBumpedOnFirst() {
    identity = authTestSupport.createIdentity();
    var sessionId = identity.session().getId();

    // A second application instance: its own cache, its own listener, same database.
    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    // Warm the second instance's cache with the stale version — from here on, only a
    // notification (never read-through) can change what it serves.
    assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(0L);

    // First instance revokes the session; the bump publishes inside the same transaction.
    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(1L));
  }

  @Test
  @DisplayName("Should converge membership version on second instance when link revoked on first")
  void shouldConvergeMembershipVersionOnSecondInstanceWhenLinkRevokedOnFirst() {
    identity = authTestSupport.createIdentity();
    var accountId = identity.account().getId();
    var householdId = identity.household().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    var staleVersion = versionCounterReader.membershipVersion(accountId, householdId).orElseThrow();
    assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
        .contains(staleVersion);

    accountProfileRepository.revokeProfileLink(
        AccountProfile.builder()
            .accountId(accountId)
            .householdId(householdId)
            .profileId(identity.profile().getId())
            .build());
    var currentVersion =
        versionCounterReader.membershipVersion(accountId, householdId).orElseThrow();
    assertThat(currentVersion).isGreaterThan(staleVersion);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
                    .contains(currentVersion));
  }

  @Test
  @DisplayName("Should converge membership version on second instance when membership granted")
  void shouldConvergeMembershipVersionOnSecondInstanceWhenMembershipGranted() {
    identity = authTestSupport.createIdentity();
    var accountId = identity.account().getId();
    var householdId = identity.household().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    var staleVersion = versionCounterReader.membershipVersion(accountId, householdId).orElseThrow();
    assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
        .contains(staleVersion);

    membershipRepository.revokeMembership(accountId, householdId).orElseThrow();
    var regranted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(accountId)
                .householdId(householdId)
                .householdRole(HouseholdRole.OWNER)
                .build());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
                    .contains(regranted.version()));
  }

  @Test
  @DisplayName("Should converge membership version on second instance when household role changed")
  void shouldConvergeMembershipVersionOnSecondInstanceWhenHouseholdRoleChanged() {
    identity = authTestSupport.createIdentity();
    var accountId = identity.account().getId();
    var householdId = identity.household().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    var staleVersion = versionCounterReader.membershipVersion(accountId, householdId).orElseThrow();
    assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
        .contains(staleVersion);

    var roleChanged =
        membershipRepository
            .changeRole(
                HouseholdMembership.builder()
                    .accountId(accountId)
                    .householdId(householdId)
                    .householdRole(HouseholdRole.PARENT)
                    .build())
            .orElseThrow();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
                    .contains(roleChanged.version()));
  }

  @Test
  @DisplayName("Should converge membership version on second instance when membership revoked")
  void shouldConvergeMembershipVersionOnSecondInstanceWhenMembershipRevoked() {
    identity = authTestSupport.createIdentity();
    var accountId = identity.account().getId();
    var householdId = identity.household().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    var staleVersion = versionCounterReader.membershipVersion(accountId, householdId).orElseThrow();
    assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
        .contains(staleVersion);

    var revoked = membershipRepository.revokeMembership(accountId, householdId).orElseThrow();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
                    .contains(revoked.version()));
  }

  @Test
  @DisplayName("Should suppress local and remote invalidation when membership mutation rolls back")
  void shouldSuppressLocalAndRemoteInvalidationWhenMembershipMutationRollsBack() {
    identity = authTestSupport.createIdentity();
    sentinelIdentity = authTestSupport.createIdentity();
    var accountId = identity.account().getId();
    var householdId = identity.household().getId();
    var sentinelAccountId = sentinelIdentity.account().getId();
    var sentinelHouseholdId = sentinelIdentity.household().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    var staleVersion = versionCounterReader.membershipVersion(accountId, householdId).orElseThrow();
    var sentinelStaleVersion =
        versionCounterReader
            .membershipVersion(sentinelAccountId, sentinelHouseholdId)
            .orElseThrow();
    assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
        .contains(staleVersion);
    assertThat(secondInstanceCache.membershipVersion(sentinelAccountId, sentinelHouseholdId))
        .contains(sentinelStaleVersion);
    assertThat(localVersionCache.membershipVersion(accountId, householdId)).contains(staleVersion);

    var rolledBackVersion = new AtomicLong();
    transactionTemplate.executeWithoutResult(
        status -> {
          var changed =
              membershipRepository
                  .changeRole(
                      HouseholdMembership.builder()
                          .accountId(accountId)
                          .householdId(householdId)
                          .householdRole(HouseholdRole.PARENT)
                          .build())
                  .orElseThrow();
          rolledBackVersion.set(changed.version());
          status.setRollbackOnly();
        });

    var sentinelChanged =
        membershipRepository
            .changeRole(
                HouseholdMembership.builder()
                    .accountId(sentinelAccountId)
                    .householdId(sentinelHouseholdId)
                    .householdRole(HouseholdRole.PARENT)
                    .build())
            .orElseThrow();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        secondInstanceCache.membershipVersion(
                            sentinelAccountId, sentinelHouseholdId))
                    .contains(sentinelChanged.version()));

    assertThat(rolledBackVersion.get()).isGreaterThan(staleVersion);
    assertThat(versionCounterReader.membershipVersion(accountId, householdId))
        .contains(staleVersion);
    assertThat(secondInstanceCache.membershipVersion(accountId, householdId))
        .contains(staleVersion);
    assertThat(localVersionCache.membershipVersion(accountId, householdId)).contains(staleVersion);
  }

  @Test
  @DisplayName("Should suppress local and remote invalidation when bumping transaction rolls back")
  void shouldSuppressLocalAndRemoteInvalidationWhenBumpingTransactionRollsBack() {
    identity = authTestSupport.createIdentity();
    sentinelIdentity = authTestSupport.createIdentity();
    var rolledBackSessionId = identity.session().getId();
    var sentinelSessionId = sentinelIdentity.session().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    assertThat(secondInstanceCache.sessionVersion(rolledBackSessionId)).contains(0L);
    assertThat(secondInstanceCache.sessionVersion(sentinelSessionId)).contains(0L);
    assertThat(localVersionCache.sessionVersion(rolledBackSessionId)).contains(0L);

    // A notification for this revoke must never leave the database: the transaction rolls back.
    transactionTemplate.executeWithoutResult(
        status -> {
          sessionRepository.revoke(
              rolledBackSessionId, SessionRevocationReason.LOGOUT, Instant.now());
          status.setRollbackOnly();
        });

    // NOTIFY delivers in commit order: once the sentinel's bump lands, a notification from the
    // rolled-back revoke would already have arrived.
    sessionRepository.revoke(sentinelSessionId, SessionRevocationReason.LOGOUT, Instant.now());
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(secondInstanceCache.sessionVersion(sentinelSessionId)).contains(1L));

    assertThat(secondInstanceCache.sessionVersion(rolledBackSessionId)).contains(0L);
    assertThat(localVersionCache.sessionVersion(rolledBackSessionId)).contains(0L);
  }

  @Test
  @DisplayName("Should deliver session notification only after bumping transaction commits")
  void shouldDeliverSessionNotificationOnlyAfterBumpingTransactionCommits() throws Exception {
    identity = authTestSupport.createIdentity();
    sentinelIdentity = authTestSupport.createIdentity();
    var sessionId = identity.session().getId();
    var sentinelSessionId = sentinelIdentity.session().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(0L);
    assertThat(secondInstanceCache.sessionVersion(sentinelSessionId)).contains(0L);

    var bumpWritten = new CountDownLatch(1);
    var allowCommit = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var committedVersion =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      _ -> {
                        var version =
                            sessionRepository.bumpVersion(sessionId, Instant.now()).orElseThrow();
                        bumpWritten.countDown();
                        awaitLatch(allowCommit);
                        return version;
                      }));

      try {
        assertThat(bumpWritten.await(10, TimeUnit.SECONDS)).isTrue();

        // A committed sentinel proves the listener drained everything deliverable while the
        // target transaction remained open. The target notification must still be invisible.
        sessionRepository.revoke(sentinelSessionId, SessionRevocationReason.LOGOUT, Instant.now());
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(secondInstanceCache.sessionVersion(sentinelSessionId)).contains(1L));
        assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(0L);
      } finally {
        allowCommit.countDown();
      }

      assertThat(committedVersion.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(1L));
  }

  @Test
  @DisplayName("Should keep applying valid notifications when malformed ones arrive first")
  void shouldKeepApplyingValidNotificationsWhenMalformedOnesArriveFirst() {
    identity = authTestSupport.createIdentity();
    var sessionId = identity.session().getId();

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(0L);

    // Garbage commits before the revoke, so the listener must survive it to see the real bump.
    publishRawNotification("garbage-with-no-delimiters");
    publishRawNotification("SESSION|" + sessionId + "|not-a-number");
    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(1L));
    assertThat(secondInstanceListener.isListening()).isTrue();
  }

  @Test
  @DisplayName("Should reject revoked session when stopped mid-notification apply")
  void shouldRejectRevokedSessionWhenStoppedMidNotificationApply() throws Exception {
    identity = authTestSupport.createIdentity();
    var sessionId = identity.session().getId();

    var applyStarted = new CountDownLatch(1);
    var stopRequested = new CountDownLatch(1);
    var holdingCache =
        new TokenVersionCache(versionCounterReader) {
          @Override
          public void update(CounterKind kind, String key, long version) {
            // Parallel test classes share the notification channel; hold only our session's
            // update so foreign bumps pass through untouched.
            if (kind == CounterKind.SESSION && key.equals(sessionId.toString())) {
              applyStarted.countDown();
              try {
                stopRequested.await();
              } catch (InterruptedException _) {
                // The stop's interrupt lands here, so the listener observes shutdown at its
                // loop condition rather than as a connection error.
              }
            }
            super.update(kind, key, version);
          }
        };
    secondInstanceListener =
        new CounterNotificationListener(holdingCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    // Warm the cache with the stale version before holding its revocation notification in-flight.
    assertThat(holdingCache.sessionVersion(sessionId)).contains(0L);

    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now());
    assertThat(applyStarted.await(10, TimeUnit.SECONDS)).isTrue();

    secondInstanceListener.stop();
    stopRequested.countDown();

    await().atMost(Duration.ofSeconds(10)).until(() -> !secondInstanceListener.isListening());
    // Stopping suspends caching. The authoritative reader excludes revoked sessions, so rejection
    // no longer depends on the in-flight notification becoming a warm cache entry.
    assertThat(holdingCache.sessionVersion(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should stop listening when stopped while connected")
  void shouldStopListeningWhenStoppedWhileConnected() {
    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(secondInstanceCache, connectionSource, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    secondInstanceListener.stop();

    assertThat(secondInstanceListener.isRunning()).isFalse();
    await().atMost(Duration.ofSeconds(10)).until(() -> !secondInstanceListener.isListening());
  }

  private void publishRawNotification(String payload) {
    dsl.select(
            DSL.function(
                "pg_notify",
                String.class,
                DSL.val(CounterNotificationPayload.CHANNEL),
                DSL.val(payload)))
        .fetch();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("transaction was not released before the timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
