package com.streamarr.server.fakes;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BlockingSegmentStore extends FakeSegmentStore {

  private final CountDownLatch preparing = new CountDownLatch(1);
  private final CountDownLatch continuePreparing = new CountDownLatch(1);

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
    var prepared = super.prepareSegment(sessionId, segmentName, data);
    preparing.countDown();
    try {
      if (!continuePreparing.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to prepare segment");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while preparing segment", e);
    }
    return prepared;
  }

  public boolean awaitPreparation(Duration timeout) throws InterruptedException {
    return preparing.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void continuePreparation() {
    continuePreparing.countDown();
  }
}
