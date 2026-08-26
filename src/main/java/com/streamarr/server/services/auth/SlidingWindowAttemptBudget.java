package com.streamarr.server.services.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;

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
  private final Semaphore availableKeySlots;
  private final ConcurrentHashMap<K, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  SlidingWindowAttemptBudget(Limits limits, Clock clock) {
    this.maximumAttempts = limits.maximumAttempts();
    this.window = limits.window();
    this.clock = clock;
    availableKeySlots = new Semaphore(limits.maximumTrackedKeys());
  }

  /** How many attempts a key gets per window, and how many keys the budget tracks at once. */
  @Builder
  record Limits(int maximumAttempts, Duration window, int maximumTrackedKeys) {

    Limits {
      if (maximumTrackedKeys <= 0) {
        throw new IllegalArgumentException("maximumTrackedKeys must be positive");
      }
    }

    static Limits unboundedKeys(int maximumAttempts, Duration window) {
      return new Limits(maximumAttempts, window, Integer.MAX_VALUE);
    }
  }

  boolean reserve(K key) {
    var reserved = new AtomicBoolean();
    attempts.compute(
        key,
        (_, timestamps) -> {
          var current = timestamps;
          if (current == null && !availableKeySlots.tryAcquire()) {
            return null;
          }

          if (current == null) {
            current = new ArrayDeque<>();
          }

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
    if (attempts.remove(key) != null) {
      availableKeySlots.release();
    }
  }

  /** Evicts keys whose attempts all fell out of the window; returns how many were evicted. */
  int sweepExpired() {
    var evicted = 0;
    for (var key : attempts.keySet()) {
      var removed = new AtomicBoolean();
      attempts.computeIfPresent(
          key,
          (_, timestamps) -> {
            var remaining = pruned(timestamps);
            if (remaining == null) {
              availableKeySlots.release();
              removed.set(true);
            }

            return remaining;
          });
      if (removed.get()) {
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
