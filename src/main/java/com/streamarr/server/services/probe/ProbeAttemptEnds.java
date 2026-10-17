package com.streamarr.server.services.probe;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Wakes the playback requests that wait for a media file's probe when an attempt at the probe ends.
 * The probe dispatcher runs on this server, so the attempt's end reaches the waiting requests in
 * process; each request then reads the stored result.
 */
@Component
public class ProbeAttemptEnds {

  private static final String WAITS_METRIC = "streamarr.playback.probe_waits";

  private final ConcurrentHashMap<UUID, AttemptEnd> nextEnds = new ConcurrentHashMap<>();

  public ProbeAttemptEnds(@NonNull MeterRegistry meterRegistry) {
    Gauge.builder(WAITS_METRIC, this, ProbeAttemptEnds::waitingRequestCount)
        .description("Playback requests that wait for a media file's probe")
        .register(meterRegistry);
  }

  /**
   * Takes the next end of an attempt at the media file's probe. Take it before reading the probe's
   * stored state, so an attempt that ends after the read still ends the wait. The request closes
   * the end when it stops waiting on it.
   */
  public NextEnd next(@NonNull UUID mediaFileId) {
    return new NextEnd(
        mediaFileId, nextEnds.compute(mediaFileId, (_, current) -> withOneMoreWaiter(current)));
  }

  public void signalEnd(@NonNull UUID mediaFileId) {
    var ended = nextEnds.remove(mediaFileId);
    if (ended != null) {
      ended.ended.countDown();
    }
  }

  private static AttemptEnd withOneMoreWaiter(AttemptEnd current) {
    var attemptEnd = current;
    if (attemptEnd == null) {
      attemptEnd = new AttemptEnd();
    }

    attemptEnd.waiters.incrementAndGet();
    return attemptEnd;
  }

  // An end that already happened has left the map, and the file's next end may have replaced it,
  // so closing the ended one must leave the next end's waiters alone.
  private static AttemptEnd withoutWaiterOf(AttemptEnd current, AttemptEnd closed) {
    if (current != closed) {
      return current;
    }

    if (current.waiters.decrementAndGet() == 0) {
      return null;
    }

    return current;
  }

  private double waitingRequestCount() {
    return nextEnds.values().stream().mapToInt(attemptEnd -> attemptEnd.waiters.get()).sum();
  }

  private static final class AttemptEnd {

    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicInteger waiters = new AtomicInteger();
  }

  /** One request's wait on the next end of an attempt, which every waiting request shares. */
  public final class NextEnd implements AutoCloseable {

    private final UUID mediaFileId;
    private final AttemptEnd attemptEnd;

    private NextEnd(UUID mediaFileId, AttemptEnd attemptEnd) {
      this.mediaFileId = mediaFileId;
      this.attemptEnd = attemptEnd;
    }

    /** Waits until the attempt ends or the timeout passes, and returns whether it ended. */
    public boolean await(@NonNull Duration timeout) throws InterruptedException {
      return attemptEnd.ended.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Ends this request's wait. The server forgets an end that no request waits for. */
    @Override
    public void close() {
      nextEnds.computeIfPresent(mediaFileId, (_, current) -> withoutWaiterOf(current, attemptEnd));
    }
  }
}
