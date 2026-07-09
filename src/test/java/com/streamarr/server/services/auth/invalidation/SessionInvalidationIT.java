package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import com.streamarr.server.services.auth.CounterKind;
import com.streamarr.server.services.auth.TokenVersionCache;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

  @Autowired private VersionCounterReader versionCounterReader;

  @Autowired private CounterNotificationConnectionSource connectionSource;

  @Autowired private CounterNotificationBackoff backoff;

  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity identity;
  private CounterNotificationListener secondInstanceListener;

  @AfterEach
  void tearDown() {
    if (secondInstanceListener != null) {
      secondInstanceListener.stop();
    }
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
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
  @DisplayName("Should finish applying an in-flight notification when stopped mid-apply")
  void shouldFinishApplyingAnInFlightNotificationWhenStoppedMidApply() throws Exception {
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

    // Warm the cache with the stale version so only the in-flight update can move it to 1.
    assertThat(holdingCache.sessionVersion(sessionId)).contains(0L);

    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now());
    assertThat(applyStarted.await(10, TimeUnit.SECONDS)).isTrue();

    secondInstanceListener.stop();
    stopRequested.countDown();

    await().atMost(Duration.ofSeconds(10)).until(() -> !secondInstanceListener.isListening());
    assertThat(holdingCache.sessionVersion(sessionId)).contains(1L);
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
}
