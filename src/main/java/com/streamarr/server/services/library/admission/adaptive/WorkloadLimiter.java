package com.streamarr.server.services.library.admission.adaptive;

import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limiter.BlockingLimiter;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import com.streamarr.server.services.library.admission.Workload;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One adaptive limiter for one workload, kept for the life of the server so later scans start from
 * what earlier ones learned. Scans and refreshes get separate limiters because their per-item round
 * trips differ (search, details and inserts versus details and updates).
 */
final class WorkloadLimiter {

  private final SimpleLimiter<Void> limiter;
  private final Limiter<Void> blocking;
  private final PositiveRttLimit limit;
  private final Duration acquireTimeout;
  private final AtomicInteger lowestLimit = new AtomicInteger();
  private final AtomicInteger highestLimit = new AtomicInteger();
  private final AtomicInteger limitChanges = new AtomicInteger();
  private final AtomicInteger peakInflight = new AtomicInteger();

  WorkloadLimiter(
      AdaptiveLimitAlgorithm algorithm,
      Workload workload,
      Duration acquireTimeout,
      MeterRegistry registry,
      boolean queueingRtt) {
    var name = workload.name().toLowerCase(Locale.ROOT);
    this.limit =
        new PositiveRttLimit(
            queueingRtt
                ? new QueueingRttLimit(algorithm.newQueueingLimit())
                : algorithm.newLimit());
    this.limiter =
        SimpleLimiter.newBuilder()
            .named("poc-" + name)
            .limit(limit)
            .metricRegistry(new LimiterCallMetrics(registry, name))
            .build();
    this.blocking = BlockingLimiter.wrap(limiter, acquireTimeout);
    this.acquireTimeout = acquireTimeout;
    limit.notifyOnChange(this::onLimitChanged);
    resetTrace();
  }

  /**
   * Blocks until a permit is free. Empty on timeout or interrupt (the interrupt flag is then set).
   * Only the walking thread may call this: every release wakes every waiter.
   */
  Optional<Limiter.Listener> acquire() {
    var permit = blocking.acquire(null);
    peakInflight.accumulateAndGet(limiter.getInflight(), Math::max);
    return permit;
  }

  int limit() {
    return limiter.getLimit();
  }

  int inflight() {
    return limiter.getInflight();
  }

  Duration acquireTimeout() {
    return acquireTimeout;
  }

  /** Starts a new min/max/changes trace for the run summary. */
  void resetTrace() {
    var current = limiter.getLimit();
    lowestLimit.set(current);
    highestLimit.set(current);
    limitChanges.set(0);
    peakInflight.set(limiter.getInflight());
  }

  String trace() {
    return "limit "
        + limiter.getLimit()
        + " (min "
        + lowestLimit.get()
        + ", max "
        + highestLimit.get()
        + ", changes "
        + limitChanges.get()
        + "), peak inflight "
        + peakInflight.get()
        + ", inflight at end "
        + limiter.getInflight()
        + ", zero-rtt samples skipped "
        + limit.skippedSamples();
  }

  private void onLimitChanged(int newLimit) {
    lowestLimit.accumulateAndGet(newLimit, Math::min);
    highestLimit.accumulateAndGet(newLimit, Math::max);
    limitChanges.incrementAndGet();
  }
}
