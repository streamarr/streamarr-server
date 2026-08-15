package com.streamarr.server.services.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class SlidingWindowAttemptBudget<K> {

  private final int maximumAttempts;
  private final Duration window;
  private final Clock clock;
  private final ConcurrentHashMap<K, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  /**
   * Creates a sliding-window attempt budget.
   *
   * @param maximumAttempts the maximum number of attempts allowed within the window
   * @param window          the duration over which attempts are counted
   * @param clock           the clock used to determine the current time
   */
  SlidingWindowAttemptBudget(int maximumAttempts, Duration window, Clock clock) {
    this.maximumAttempts = maximumAttempts;
    this.window = window;
    this.clock = clock;
  }

  /**
   * Attempts to reserve an attempt for the specified key within the configured sliding window.
   * Null keys are always accepted without tracking.
   *
   * @param key the key whose attempt budget is used
   * @return {@code true} if the reservation succeeds, {@code false} if the key has reached its limit
   */
  boolean reserve(K key) {
    if (key == null) {
      return true;
    }

    var reserved = new AtomicBoolean();
    attempts.compute(
        key,
        (_, timestamps) -> {
          var current = timestamps == null ? new ArrayDeque<Instant>() : timestamps;
          prune(current);
          if (current.size() < maximumAttempts) {
            current.addLast(clock.instant());
            reserved.set(true);
          }
          return current;
        });
    return reserved.get();
  }

  /**
   * Clears all tracked attempts for the specified key.
   *
   * @param key the key whose tracked attempts are cleared
   */
  void reset(K key) {
    if (key != null) {
      attempts.remove(key);
    }
  }

  /**
   * Releases the most recent reservation associated with the key.
   *
   * @param key the key whose most recent reservation should be released
   */
  void release(K key) {
    if (key == null) {
      return;
    }

    attempts.computeIfPresent(
        key,
        (_, timestamps) -> {
          timestamps.pollLast();
          return timestamps.isEmpty() ? null : timestamps;
        });
  }

  /**
   * Removes expired attempt histories and empty entries from the budget.
   *
   * @return the number of keys whose attempt histories were removed
   */
  int sweepExpired() {
    var evicted = 0;
    for (var key : attempts.keySet()) {
      var remaining = attempts.computeIfPresent(key, (_, timestamps) -> pruned(timestamps));
      if (remaining == null) {
        evicted++;
      }
    }
    return evicted;
  }

  /**
   * Removes expired timestamps and returns the remaining timestamp deque.
   *
   * @param timestamps the deque of attempt timestamps to prune
   * @return the deque containing unexpired timestamps, or {@code null} if none remain
   */
  private Deque<Instant> pruned(Deque<Instant> timestamps) {
    prune(timestamps);
    return timestamps.isEmpty() ? null : timestamps;
  }

  /**
   * Removes timestamps that fall before the current sliding-window cutoff.
   *
   * @param timestamps the deque of attempt timestamps to prune
   */
  private void prune(Deque<Instant> timestamps) {
    var cutoff = clock.instant().minus(window);
    while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
      timestamps.pollFirst();
    }
  }
}
