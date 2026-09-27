package com.streamarr.server.services.library.admission.adaptive;

import com.netflix.concurrency.limits.Limit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Replaces each sample's round trip with {@code smallest service time seen + this task's queueing
 * time}, where service time is the round trip minus the queueing time ({@link QueueingDelay}).
 * The wrapped delay-based limit then sees a round trip that grows only when tasks queue at the
 * TMDB gate or the connection pool, not when one task simply has more work than another. Samples
 * without a queueing time pass through unchanged.
 */
final class QueueingRttLimit implements Limit {

  private final Limit delegate;
  private final AtomicLong smallestServiceNanos = new AtomicLong(Long.MAX_VALUE);

  QueueingRttLimit(Limit delegate) {
    this.delegate = delegate;
  }

  @Override
  public int getLimit() {
    return delegate.getLimit();
  }

  @Override
  public void notifyOnChange(Consumer<Integer> consumer) {
    delegate.notifyOnChange(consumer);
  }

  @Override
  public void onSample(long startTime, long rtt, int inflight, boolean didDrop) {
    if (!QueueingDelay.SAMPLE_WAIT.isBound()) {
      delegate.onSample(startTime, rtt, inflight, didDrop);
      return;
    }

    var waitNanos = Math.clamp(QueueingDelay.SAMPLE_WAIT.get(), 0, rtt);
    var serviceNanos = Math.max(1, rtt - waitNanos);
    var smallest = smallestServiceNanos.accumulateAndGet(serviceNanos, Math::min);
    delegate.onSample(startTime, smallest + waitNanos, inflight, didDrop);
  }
}
