package com.streamarr.server.services.library.admission.adaptive;

import com.netflix.concurrency.limits.Limit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Passes only samples with a positive round-trip time to the wrapped limit.
 *
 * <p>{@code SimpleLimiter} releases its semaphore after the limit has taken the sample, so a limit
 * that throws keeps the permit forever: {@code VegasLimit} throws for {@code rtt <= 0}. A zero
 * sample also carries no delay information (Gradient2 reads it as "no queueing" and grows). Such a
 * sample is therefore skipped, which releases the permit exactly as {@code onIgnore} would.
 */
final class PositiveRttLimit implements Limit {

  private final Limit delegate;
  private final LongAdder skippedSamples = new LongAdder();

  PositiveRttLimit(Limit delegate) {
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
    if (rtt <= 0) {
      skippedSamples.increment();
      return;
    }

    delegate.onSample(startTime, rtt, inflight, didDrop);
  }

  long skippedSamples() {
    return skippedSamples.sum();
  }
}
