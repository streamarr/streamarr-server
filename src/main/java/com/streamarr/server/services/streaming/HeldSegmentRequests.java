package com.streamarr.server.services.streaming;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Wakes the segment requests that the server holds until their segment is stored. A held request
 * takes its variant's next wake before it looks for its segment, so a segment stored or a job
 * attempt ended while it looks still wakes it.
 */
public final class HeldSegmentRequests {

  private final ConcurrentHashMap<VariantKey, Wake> nextWakes = new ConcurrentHashMap<>();

  /** The wake that the variant's next stored segment or ended job attempt fires. */
  public Wake nextWake(UUID sessionId, String variantLabel) {
    return nextWakes.computeIfAbsent(new VariantKey(sessionId, variantLabel), _ -> new Wake());
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

  private record VariantKey(UUID sessionId, String variantLabel) {}

  /** One wake, shared by every request held on a variant until the wake fires. */
  public static final class Wake {

    private final CountDownLatch fired = new CountDownLatch(1);

    private Wake() {}

    /** Blocks until the wake fires or the timeout elapses; answers whether the wake fired. */
    public boolean await(Duration timeout) throws InterruptedException {
      return fired.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
  }
}
