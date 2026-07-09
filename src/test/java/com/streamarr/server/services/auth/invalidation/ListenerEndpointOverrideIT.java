package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.CounterListenerProperties;
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
 * PgBouncer transaction pooling cannot carry LISTEN/NOTIFY, so deployments routing application
 * traffic through a pooler must be able to point only the listener at PostgreSQL directly (ADR
 * 0016). The pooler here is simply an endpoint the listener cannot use.
 */
@Tag("IntegrationTest")
@DisplayName("Listener Endpoint Override Integration Tests")
class ListenerEndpointOverrideIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private AuthSessionRepository sessionRepository;

  @Autowired private VersionCounterReader versionCounterReader;

  @Autowired private CounterNotificationBackoff backoff;

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
  @DisplayName("Should listen through the override endpoint when the datasource cannot carry it")
  void shouldListenThroughOverrideEndpointWhenDatasourceCannotCarryIt() {
    identity = authTestSupport.createIdentity();
    var sessionId = identity.session().getId();

    var overrideProperties =
        CounterListenerProperties.builder()
            .jdbcUrl(connectionDetails.getJdbcUrl())
            .username(connectionDetails.getUsername())
            .password(connectionDetails.getPassword())
            .build();
    var source =
        new JdbcCounterNotificationConnectionSource(unlistenableDetails(), overrideProperties);

    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener = new CounterNotificationListener(secondInstanceCache, source, backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(0L);

    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(1L));
  }

  /** An application datasource endpoint the listener must not use — nothing answers here. */
  private static JdbcConnectionDetails unlistenableDetails() {
    return new JdbcConnectionDetails() {
      @Override
      public String getJdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:1/streamarr";
      }

      @Override
      public String getUsername() {
        return "streamarr";
      }

      @Override
      public String getPassword() {
        return "unused";
      }
    };
  }
}
