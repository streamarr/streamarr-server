package com.streamarr.server.fakes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {

  private final AtomicReference<Instant> currentTime;

  public MutableClock(AtomicReference<Instant> currentTime) {
    this.currentTime = currentTime;
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
