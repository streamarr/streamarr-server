package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import com.streamarr.server.services.auth.TokenVersionCache;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

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

  @Autowired private JdbcConnectionDetails connectionDetails;

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
        new CounterNotificationListener(secondInstanceCache, connectionDetails);
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
}
