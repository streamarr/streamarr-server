package com.streamarr.server.fakes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A clock that can park one call to {@link #instant()} at a deterministic race boundary. */
public final class GatedClock extends Clock {

  private final Clock delegate;
  private final AtomicBoolean blockNextCall = new AtomicBoolean();
  private final CountDownLatch blockedCall = new CountDownLatch(1);
  private final CountDownLatch releaseBlockedCall = new CountDownLatch(1);

  public GatedClock(Clock delegate) {
    this.delegate = delegate;
  }

  public void blockNextCall() {
    blockNextCall.set(true);
  }

  public boolean awaitBlockedCall() throws InterruptedException {
    return blockedCall.await(5, TimeUnit.SECONDS);
  }

  public void releaseBlockedCall() {
    releaseBlockedCall.countDown();
  }

  @Override
  public ZoneId getZone() {
    return delegate.getZone();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return delegate.withZone(zone);
  }

  @Override
  public Instant instant() {
    if (blockNextCall.compareAndSet(true, false)) {
      blockedCall.countDown();
      awaitRelease();
    }
    return delegate.instant();
  }

  private void awaitRelease() {
    try {
      releaseBlockedCall.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while holding the throttle race gate.");
    }
  }
}
