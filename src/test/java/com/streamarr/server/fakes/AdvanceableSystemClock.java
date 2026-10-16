package com.streamarr.server.fakes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The system clock moved ahead by an offset that a test advances. Time keeps passing, so a
 * scheduler that polls and sends heartbeats on this clock runs as usual, and a test advances past a
 * retry delay instead of waiting for it.
 */
public final class AdvanceableSystemClock extends Clock {

  private final Clock system = Clock.systemUTC();
  private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

  public void advance(Duration duration) {
    offset.accumulateAndGet(duration, Duration::plus);
  }

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
    return system.instant().plus(offset.get());
  }
}
