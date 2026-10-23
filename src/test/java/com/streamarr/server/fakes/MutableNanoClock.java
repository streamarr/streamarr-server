package com.streamarr.server.fakes;

import com.streamarr.server.services.concurrency.NanoClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class MutableNanoClock implements NanoClock {

  private final AtomicLong nanos = new AtomicLong();

  public void advance(Duration duration) {
    nanos.addAndGet(duration.toNanos());
  }

  @Override
  public long nanoTime() {
    return nanos.get();
  }
}
