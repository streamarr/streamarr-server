package com.streamarr.server.fakes;

import com.streamarr.server.services.library.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sleeps without blocking by advancing a per-thread virtual clock, so concurrent waits each observe
 * only their own elapsed time. Records the total virtual time slept across all threads.
 */
public class VirtualTimeSleeper implements Sleeper {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private final ThreadLocal<Duration> elapsed = ThreadLocal.withInitial(() -> Duration.ZERO);
  private final AtomicReference<Duration> totalSlept = new AtomicReference<>(Duration.ZERO);

  @Override
  public void sleep(Duration duration) {
    elapsed.set(elapsed.get().plus(duration));
    totalSlept.accumulateAndGet(duration, Duration::plus);
  }

  public Clock clock() {
    return new ThreadClock();
  }

  public Duration totalSlept() {
    return totalSlept.get();
  }

  private final class ThreadClock extends Clock {

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return START.plus(elapsed.get());
    }
  }
}
