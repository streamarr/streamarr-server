package com.streamarr.server.services.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An in-memory sliding-window attempt counter per key. {@link #reserve} takes a slot atomically
 * before any expensive work so a concurrent burst cannot overrun the budget; a blocked attempt
 * reserves nothing, so hostile traffic cannot extend a lockout. Single-JVM, like {@link
 * LoginThrottle}.
 */
final class SlidingWindowAttemptBudget<K> {

  private final int maximumAttempts;
  private final Duration window;
  private final Clock clock;
  private final ConcurrentHashMap<K, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  SlidingWindowAttemptBudget(int maximumAttempts, Duration window, Clock clock) {
    this.maximumAttempts = maximumAttempts;
    this.window = window;
    this.clock = clock;
  }

  boolean reserve(K key) {
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

  void reset(K key) {
    attempts.remove(key);
  }

  /** Evicts keys whose attempts all fell out of the window; returns how many were evicted. */
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

  private Deque<Instant> pruned(Deque<Instant> timestamps) {
    prune(timestamps);
    return timestamps.isEmpty() ? null : timestamps;
  }

  private void prune(Deque<Instant> timestamps) {
    var cutoff = clock.instant().minus(window);
    while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
      timestamps.pollFirst();
    }
  }
}
