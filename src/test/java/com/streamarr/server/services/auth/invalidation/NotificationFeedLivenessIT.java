package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import com.streamarr.server.services.auth.TokenVersionCache;
import com.streamarr.server.support.AuthTestSupport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * A LISTEN connection can die without ever erroring: a NAT idle-drop or non-RST failover leaves the
 * socket open while delivering nothing, and pgjdbc's getNotifications only reads buffered data. The
 * listener must detect that on its own and reconverge — otherwise this instance serves revoked
 * sessions until restart.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Feed Liveness Integration Tests")
class NotificationFeedLivenessIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private AuthSessionRepository sessionRepository;

  @Autowired private VersionCounterReader versionCounterReader;

  @Autowired private CounterNotificationBackoff backoff;

  @Autowired private JdbcConnectionDetails connectionDetails;

  private AuthTestSupport.TestIdentity identity;
  private CounterNotificationListener secondInstanceListener;
  private BlackholeTcpProxy proxy;

  @AfterEach
  void tearDown() {
    if (secondInstanceListener != null) {
      secondInstanceListener.stop();
    }
    if (proxy != null) {
      proxy.close();
    }
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should reconverge when the notification connection silently stops delivering")
  void shouldReconvergeWhenNotificationConnectionSilentlyStopsDelivering() throws IOException {
    identity = authTestSupport.createIdentity();
    var sessionId = identity.session().getId();

    proxy = BlackholeTcpProxy.forJdbcUrl(connectionDetails.getJdbcUrl());
    var secondInstanceCache = new TokenVersionCache(versionCounterReader);
    secondInstanceListener =
        new CounterNotificationListener(
            secondInstanceCache,
            new JdbcCounterNotificationConnectionSource(proxy.proxiedDetails(connectionDetails)),
            backoff);
    secondInstanceListener.start();
    await().atMost(Duration.ofSeconds(10)).until(secondInstanceListener::isListening);

    // Warm the second instance's cache with the stale version.
    assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(0L);

    // The feed goes dead without a single socket error; nothing external will wake the listener.
    proxy.blackhole();
    await().atMost(Duration.ofSeconds(25)).until(() -> !secondInstanceListener.isListening());

    proxy.heal();
    await().atMost(Duration.ofSeconds(15)).until(secondInstanceListener::isListening);

    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now());
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(secondInstanceCache.sessionVersion(sessionId)).contains(1L));
  }

  /**
   * Forwards bytes between the listener and PostgreSQL. Black-holing keeps every socket open but
   * silently drops all data in both directions — the half-open state a NAT or failover produces.
   */
  private static final class BlackholeTcpProxy implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final String targetHost;
    private final int targetPort;
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private volatile boolean blackholed;

    static BlackholeTcpProxy forJdbcUrl(String jdbcUrl) throws IOException {
      var uri = URI.create(jdbcUrl.substring("jdbc:".length()));
      var port = uri.getPort() == -1 ? 5432 : uri.getPort();
      return new BlackholeTcpProxy(uri.getHost(), port);
    }

    private BlackholeTcpProxy(String targetHost, int targetPort) throws IOException {
      this.targetHost = targetHost;
      this.targetPort = targetPort;
      this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      Thread.ofVirtual().name("blackhole-proxy-accept").start(this::acceptLoop);
    }

    JdbcConnectionDetails proxiedDetails(JdbcConnectionDetails original) {
      var uri = URI.create(original.getJdbcUrl().substring("jdbc:".length()));
      var query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
      var proxiedUrl =
          "jdbc:postgresql://127.0.0.1:" + serverSocket.getLocalPort() + uri.getPath() + query;
      return new JdbcConnectionDetails() {
        @Override
        public String getJdbcUrl() {
          return proxiedUrl;
        }

        @Override
        public String getUsername() {
          return original.getUsername();
        }

        @Override
        public String getPassword() {
          return original.getPassword();
        }
      };
    }

    void blackhole() {
      blackholed = true;
    }

    void heal() {
      blackholed = false;
      // A healed partition does not resurrect old flows; drop them so stalled clients fail fast.
      openSockets.forEach(this::closeQuietly);
      openSockets.clear();
    }

    @Override
    public void close() {
      try {
        serverSocket.close();
      } catch (IOException _) {
        // Nothing left to release.
      }
      openSockets.forEach(this::closeQuietly);
    }

    private void acceptLoop() {
      while (!serverSocket.isClosed()) {
        try {
          var client = serverSocket.accept();
          var target = new Socket(targetHost, targetPort);
          openSockets.add(client);
          openSockets.add(target);
          Thread.ofVirtual().start(() -> pump(client, target));
          Thread.ofVirtual().start(() -> pump(target, client));
        } catch (IOException _) {
          return;
        }
      }
    }

    private void pump(Socket from, Socket to) {
      try {
        var in = from.getInputStream();
        var out = to.getOutputStream();
        var buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          if (!blackholed) {
            out.write(buffer, 0, read);
            out.flush();
          }
        }
      } catch (IOException _) {
        // The pump ends with its socket.
      }
    }

    private void closeQuietly(Socket socket) {
      try {
        socket.close();
      } catch (IOException _) {
        // Already gone.
      }
    }
  }
}
