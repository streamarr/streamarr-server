package com.streamarr.server.fakes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {

  private final AtomicReference<Instant> currentTime;

  public MutableClock() {
    this(new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z")));
  }

  public MutableClock(AtomicReference<Instant> currentTime) {
    this.currentTime = currentTime;
  }

  public void advance(Duration duration) {
    currentTime.updateAndGet(instant -> instant.plus(duration));
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
    return currentTime.get();
  }
}
