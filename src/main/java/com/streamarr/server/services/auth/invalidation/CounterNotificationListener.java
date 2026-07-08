package com.streamarr.server.services.auth.invalidation;

import com.streamarr.server.services.auth.TokenVersionCache;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Cross-instance invalidation: counter bumps publish through pg_notify inside their transaction
 * (commit-bound atomicity for free), and this listener applies them to the local version cache. It
 * holds one dedicated JDBC connection — never a Hikari lease — on a virtual thread, and clears the
 * whole cache on every (re)connect: notifications missed while disconnected would otherwise leave
 * stale entries, and a cleared cache lazily refills. PgBouncer transaction pooling does not carry
 * LISTEN/NOTIFY; see docs/architecture.adoc.
 */
@Slf4j
@Component
public class CounterNotificationListener implements SmartLifecycle {

  private static final int POLL_TIMEOUT_MS = 500;
  private static final long INITIAL_BACKOFF_MS = 1_000;
  private static final long MAX_BACKOFF_MS = 30_000;

  private final TokenVersionCache cache;
  private final JdbcConnectionDetails connectionDetails;
  private final SecureRandom jitterSource = new SecureRandom();

  private volatile boolean running;
  private volatile boolean listening;
  private Thread worker;

  public CounterNotificationListener(
      TokenVersionCache cache, JdbcConnectionDetails connectionDetails) {
    this.cache = cache;
    this.connectionDetails = connectionDetails;
  }

  @Override
  public void start() {
    running = true;
    worker = Thread.ofVirtual().name("counter-notification-listener").start(this::listenLoop);
  }

  @Override
  public void stop() {
    running = false;
    if (worker != null) {
      worker.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  public boolean isListening() {
    return listening;
  }

  private void listenLoop() {
    var backoffMs = INITIAL_BACKOFF_MS;

    while (running) {
      try (var connection = openConnection()) {
        try (var statement = connection.createStatement()) {
          statement.execute("LISTEN " + CounterNotificationPayload.CHANNEL);
        }
        // Anything published while we were away is lost; stale entries must not survive.
        cache.clearAll();
        listening = true;
        backoffMs = INITIAL_BACKOFF_MS;

        var pgConnection = connection.unwrap(PGConnection.class);
        while (running) {
          var notifications = pgConnection.getNotifications(POLL_TIMEOUT_MS);
          if (notifications == null) {
            continue;
          }
          for (var notification : notifications) {
            apply(notification.getParameter());
          }
        }
      } catch (SQLException e) {
        listening = false;
        if (!running) {
          return;
        }
        log.warn("Counter notification connection failed; reconnecting.", e);
        sleepWithJitter(backoffMs);
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
      }
    }
    listening = false;
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        connectionDetails.getJdbcUrl(),
        connectionDetails.getUsername(),
        connectionDetails.getPassword());
  }

  private void apply(String payload) {
    CounterNotificationPayload.parse(payload)
        .ifPresentOrElse(
            notification ->
                cache.update(notification.kind(), notification.key(), notification.version()),
            () -> log.warn("Ignoring malformed counter notification: {}", payload));
  }

  private void sleepWithJitter(long backoffMs) {
    try {
      Thread.sleep(backoffMs + jitterSource.nextLong(backoffMs / 2 + 1));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
