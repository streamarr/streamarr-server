package com.streamarr.server.services.streaming;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;

/**
 * Wakes the segment requests that the server holds until their segment is stored. A held request
 * takes its variant's next wake before it looks for its segment, so a segment stored or a job
 * attempt ended while it looks still wakes it.
 */
public final class HeldSegmentRequests {

  private static final String HELD_REQUESTS_METRIC = "streamarr.streaming.held_segment_requests";

  private final ConcurrentHashMap<VariantKey, VariantWake> nextWakes = new ConcurrentHashMap<>();

  public HeldSegmentRequests(@NonNull MeterRegistry meterRegistry) {
    Gauge.builder(HELD_REQUESTS_METRIC, this, HeldSegmentRequests::heldRequestCount)
        .description("Segment requests that hold their variant's next wake")
        .register(meterRegistry);
  }

  /**
   * Takes the wake that the variant's next stored segment or ended job attempt fires. The request
   * closes the wake when it stops waiting on it.
   */
  public Wake nextWake(UUID sessionId, String variantLabel) {
    var key = new VariantKey(sessionId, variantLabel);
    return new Wake(key, nextWakes.compute(key, (_, current) -> withOneMoreHolder(current)));
  }

  /**
   * Wakes the requests held on the variant: one of its segments is stored or a job attempt ended.
   */
  public void wakeVariant(UUID sessionId, String variantLabel) {
    fire(new VariantKey(sessionId, variantLabel));
  }

  /** Wakes every request held on the stream session, such as when the session is destroyed. */
  public void wakeSession(UUID sessionId) {
    nextWakes.keySet().stream()
        .filter(key -> key.sessionId().equals(sessionId))
        .toList()
        .forEach(this::fire);
  }

  private void fire(VariantKey key) {
    var wake = nextWakes.remove(key);
    if (wake != null) {
      wake.fired.countDown();
    }
  }

  private static VariantWake withOneMoreHolder(VariantWake current) {
    var wake = current;
    if (wake == null) {
      wake = new VariantWake();
    }

    wake.holders.incrementAndGet();
    return wake;
  }

  // A wake that already fired has left the map, and the variant's next wake may have replaced it,
  // so closing the fired wake must leave the next wake's holders alone.
  private static VariantWake withoutHolderOf(VariantWake current, VariantWake closed) {
    if (current != closed) {
      return current;
    }

    if (current.holders.decrementAndGet() == 0) {
      return null;
    }

    return current;
  }

  private double heldRequestCount() {
    return nextWakes.values().stream().mapToInt(wake -> wake.holders.get()).sum();
  }

  private record VariantKey(UUID sessionId, String variantLabel) {}

  private static final class VariantWake {

    private final CountDownLatch fired = new CountDownLatch(1);
    private final AtomicInteger holders = new AtomicInteger();
  }

  /** One request's hold on its variant's next wake, which every request held there shares. */
  public final class Wake implements AutoCloseable {

    private final VariantKey key;
    private final VariantWake variantWake;

    private Wake(VariantKey key, VariantWake variantWake) {
      this.key = key;
      this.variantWake = variantWake;
    }

    /** Blocks until the wake fires or the timeout elapses; answers whether the wake fired. */
    public boolean await(Duration timeout) throws InterruptedException {
      return variantWake.fired.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Ends this request's hold. The variant forgets an unfired wake that no request holds. */
    @Override
    public void close() {
      nextWakes.computeIfPresent(key, (_, current) -> withoutHolderOf(current, variantWake));
    }
  }
}
