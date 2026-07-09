package com.streamarr.server.services.auth.invalidation;

import com.streamarr.server.services.auth.TokenVersionCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class CounterNotificationListener implements SmartLifecycle {

  private static final int POLL_TIMEOUT_MS = 500;
  private static final long INITIAL_BACKOFF_MS = 1_000;
  private static final long MAX_BACKOFF_MS = 30_000;

  private final TokenVersionCache cache;
  private final CounterNotificationConnectionSource connectionSource;
  private final CounterNotificationBackoff backoff;

  private volatile boolean running;
  private volatile boolean listening;
  private Thread worker;

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
      try (var connection = connectionSource.open()) {
        connection.listen();
        // Anything published while we were away is lost; stale entries must not survive.
        cache.clearAll();
        listening = true;
        backoffMs = INITIAL_BACKOFF_MS;

        while (running) {
          for (var payload : connection.notifications(POLL_TIMEOUT_MS)) {
            apply(payload);
          }
        }
      } catch (CounterNotificationConnectionException e) {
        listening = false;
        if (!running) {
          return;
        }
        log.warn("Counter notification connection failed; reconnecting.", e);
        backoff.sleep(backoffMs);
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
      }
    }
    listening = false;
  }

  private void apply(String payload) {
    CounterNotificationPayload.parse(payload)
        .ifPresentOrElse(
            notification ->
                cache.update(notification.kind(), notification.key(), notification.version()),
            () -> log.warn("Ignoring malformed counter notification: {}", payload));
  }
}
