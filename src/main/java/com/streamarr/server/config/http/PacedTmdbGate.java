package com.streamarr.server.config.http;

import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * GCRA pacing at {@code rate} requests per second with {@code burst} of idle credit: one
 * theoretical arrival time (TAT) in an {@link AtomicLong}.
 *
 * <p>With a 1 s burst this paces exactly like Guava's {@code RateLimiter.create(rate)}
 * (SmoothBursty, maxBurstSeconds = 1): after one second or more of idleness {@code rate + 1}
 * requests pass at once, then one per {@code 1/rate} s. Unlike Guava it exposes its queue: {@link
 * #backlogNanos()} is how far the schedule runs ahead of now, that is the wait a request reserving
 * now would get.
 *
 * <p>Background work (secondary person and company artwork) reserves through {@link
 * #awaitBackgroundSlot}. In FIFO mode it queues like any request, and {@link #coreBacklogNanos()}
 * leaves its queued slots out. In yield mode it takes only a slot that has already gone unused, out
 * of idle credit, so it never queues ahead of core work (API calls and required artwork), never
 * delays a core request, and waits while core work keeps the gate busy.
 */
public final class PacedTmdbGate {

  private static final long MIN_YIELD_POLL_NANOS = 1_000_000L;

  private final double rate;
  private final long intervalNanos;
  private final long burstNanos;
  private final AtomicLong theoreticalArrivalNanos = new AtomicLong(System.nanoTime());
  private final LongAdder apiRequests = new LongAdder();
  private final LongAdder imageRequests = new LongAdder();
  private final LongAdder retries = new LongAdder();
  private final AtomicInteger queuedBackground = new AtomicInteger();
  private final LongAdder backgroundRequests = new LongAdder();

  public PacedTmdbGate(double rate, Duration burst) {
    if (!(rate > 0)) {
      throw new IllegalArgumentException("rate must be positive: " + rate);
    }

    this.rate = rate;
    this.intervalNanos = (long) (1e9 / rate);
    this.burstNanos = burst.toNanos();
  }

  /** Reserves the next slot without blocking; returns how long the caller must wait for it. */
  public long reserveNanos() {
    var now = System.nanoTime();
    var previous =
        theoreticalArrivalNanos.getAndUpdate(tat -> Math.max(tat, now - burstNanos) + intervalNanos);
    return Math.max(0, Math.max(previous, now - burstNanos) - now);
  }

  /** Queued demand: the wait a request reserving now would get. 0 when there is credit. */
  public long backlogNanos() {
    return Math.max(0, theoreticalArrivalNanos.get() - System.nanoTime());
  }

  /**
   * Queued demand without the slots background work holds: what an admission decision about core
   * work should compare. Equals {@link #backlogNanos()} when no background request is queued.
   */
  public long coreBacklogNanos() {
    return Math.max(0, backlogNanos() - queuedBackground.get() * intervalNanos);
  }

  /**
   * Takes a slot only when at least one whole slot has already gone unused (the schedule runs at
   * least one interval behind now). The slot comes out of idle credit, so the schedule never moves
   * past now and a request reserving right after it waits no longer than it would have. Never
   * waits.
   *
   * @return true when a slot was taken
   */
  public boolean tryReserveIdleSlot() {
    while (true) {
      var now = System.nanoTime();
      var tat = theoreticalArrivalNanos.get();
      if (tat > now - intervalNanos) {
        return false;
      }

      var start = Math.max(tat, now - burstNanos);
      if (theoreticalArrivalNanos.compareAndSet(tat, start + intervalNanos)) {
        return true;
      }
    }
  }

  /** How long until the schedule could have an unused slot, if nothing else reserves meanwhile. */
  private long untilIdleSlotNanos() {
    return Math.max(
        theoreticalArrivalNanos.get() + intervalNanos - System.nanoTime(), MIN_YIELD_POLL_NANOS);
  }

  /**
   * Waits, uninterruptibly, for a slot for background work. With {@code yieldToCore} the caller
   * polls for a free slot and so only uses capacity core work leaves idle; an interrupt ends the
   * yielding and the caller queues FIFO. Without it the caller queues FIFO, counted in {@link
   * #coreBacklogNanos()} as background.
   *
   * @return the nanoseconds waited
   */
  public long awaitBackgroundSlot(boolean yieldToCore) {
    backgroundRequests.increment();
    var startedNanos = System.nanoTime();
    if (yieldToCore) {
      while (!tryReserveIdleSlot()) {
        if (Thread.currentThread().isInterrupted()) {
          return queueBackground() + (System.nanoTime() - startedNanos);
        }

        Uninterruptibles.sleepUninterruptibly(untilIdleSlotNanos(), TimeUnit.NANOSECONDS);
      }

      return System.nanoTime() - startedNanos;
    }

    return queueBackground();
  }

  private long queueBackground() {
    var waitNanos = reserveNanos();
    queuedBackground.incrementAndGet();
    try {
      Uninterruptibles.sleepUninterruptibly(waitNanos, TimeUnit.NANOSECONDS);
    } finally {
      queuedBackground.decrementAndGet();
    }

    return waitNanos;
  }

  /** Background (secondary artwork) requests that asked this gate for a slot. */
  public long backgroundRequests() {
    return backgroundRequests.sum();
  }

  public long intervalNanos() {
    return intervalNanos;
  }

  public long burstNanos() {
    return burstNanos;
  }

  public double rate() {
    return rate;
  }

  void countDispatched(boolean image) {
    if (image) {
      imageRequests.increment();
    } else {
      apiRequests.increment();
    }
  }

  /** Called by the TMDB retry listener once per retry. */
  public void countRetry() {
    retries.increment();
  }

  /** Requests that passed the gate (every attempt, cache hits included when the gate is a client interceptor). */
  public long apiRequests() {
    return apiRequests.sum();
  }

  public long imageRequests() {
    return imageRequests.sum();
  }

  public long retries() {
    return retries.sum();
  }
}
