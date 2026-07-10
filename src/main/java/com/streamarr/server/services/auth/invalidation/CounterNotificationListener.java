package com.streamarr.server.services.auth.invalidation;

import com.streamarr.server.repositories.auth.CounterNotificationPayload;
import com.streamarr.server.services.auth.TokenVersionCache;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Cross-instance invalidation: counter bumps publish through pg_notify inside their transaction
 * (commit-bound atomicity for free), and this listener applies them to the local version cache. It
 * holds one dedicated JDBC connection — never a Hikari lease — on a virtual thread. Caching is
 * suspended on startup and every connection loss, then resumed only after LISTEN succeeds:
 * notifications missed while disconnected must not leave stale entries, so lookups read through
 * until the feed is back. PgBouncer transaction pooling does not carry LISTEN/NOTIFY; see
 * docs/architecture.adoc.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterNotificationListener implements SmartLifecycle {

  private static final int POLL_TIMEOUT_MS = 500;
  private static final long INITIAL_BACKOFF_MS = 1_000;
  private static final long MAX_BACKOFF_MS = 30_000;
  // Five failed attempts is past the exponential ramp (1+2+4+8+16s): no longer a blip.
  private static final int ERROR_ESCALATION_ATTEMPTS = 5;

  private final TokenVersionCache cache;
  private final CounterNotificationConnectionSource connectionSource;
  private final CounterNotificationBackoff backoff;

  private volatile boolean running;
  private volatile boolean listening;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final Object lifecycleMonitor = new Object();
  private Thread worker;

  @Override
  public void start() {
    synchronized (lifecycleMonitor) {
      if (running) {
        return;
      }
      cache.suspendCaching();
      running = true;
      worker = Thread.ofVirtual().name("counter-notification-listener").unstarted(this::listenLoop);
      worker.start();
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleMonitor) {
      running = false;
      cache.suspendCaching();
      if (worker != null) {
        worker.interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  public boolean isListening() {
    return listening;
  }

  public int consecutiveConnectionFailures() {
    return consecutiveFailures.get();
  }

  private void listenLoop() {
    try {
      reconnectLoop();
    } finally {
      // No exit path — not even an Error or stop racing LISTEN — may leave caching enabled or
      // strand the flag at true on a dead worker.
      synchronized (lifecycleMonitor) {
        if (worker == Thread.currentThread()) {
          cache.suspendCaching();
          listening = false;
          worker = null;
        }
      }
    }
  }

  private void reconnectLoop() {
    var backoffMs = INITIAL_BACKOFF_MS;

    while (running) {
      try (var connection = connectionSource.open()) {
        connection.listen();
        if (!resumeAfterListenIfRunning()) {
          return;
        }
        backoffMs = INITIAL_BACKOFF_MS;
        consecutiveFailures.set(0);
        consumeNotifications(connection);
      } catch (CounterNotificationConnectionException e) {
        if (disconnected()) {
          return;
        }
        logConnectionFailure(e);
        backoffMs = sleepAndGrow(backoffMs);
      } catch (RuntimeException e) {
        if (disconnected()) {
          return;
        }
        log.error("Unexpected counter notification listener failure; reconnecting.", e);
        backoffMs = sleepAndGrow(backoffMs);
      }
    }
  }

  private boolean resumeAfterListenIfRunning() {
    synchronized (lifecycleMonitor) {
      if (!running || worker != Thread.currentThread()) {
        return false;
      }
      // Anything published while we were away is lost; stale entries must not survive.
      cache.resumeCaching();
      listening = true;
      return true;
    }
  }

  private boolean disconnected() {
    listening = false;
    consecutiveFailures.incrementAndGet();
    // Bumps are invisible while the feed is down, so no successful read may become a cache hit
    // until LISTEN succeeds again.
    cache.suspendCaching();
    return !running;
  }

  private void logConnectionFailure(CounterNotificationConnectionException e) {
    var failureCount = consecutiveFailures.get();
    if (failureCount >= ERROR_ESCALATION_ATTEMPTS) {
      log.error(
          "Counter notifications unavailable after {} consecutive attempts;"
              + " caches serve read-through until reconnected.",
          failureCount,
          e);
      return;
    }
    log.warn("Counter notification connection failed; reconnecting.", e);
  }

  private void consumeNotifications(CounterNotificationConnection connection) {
    while (running) {
      for (var payload : connection.notifications(POLL_TIMEOUT_MS)) {
        apply(payload);
      }
    }
  }

  private long sleepAndGrow(long backoffMs) {
    backoff.sleep(backoffMs);
    return Math.min(backoffMs * 2, MAX_BACKOFF_MS);
  }

  private void apply(String payload) {
    CounterNotificationPayload.parse(payload)
        .ifPresentOrElse(
            notification ->
                cache.update(notification.kind(), notification.key(), notification.version()),
            () -> failClosedOnMalformed(payload));
  }

  private void failClosedOnMalformed(String payload) {
    // An unparseable payload may represent a missed invalidation, so discard every cached version.
    log.error("Clearing version cache on malformed counter notification: {}", payload);
    cache.clearAll();
  }
}
