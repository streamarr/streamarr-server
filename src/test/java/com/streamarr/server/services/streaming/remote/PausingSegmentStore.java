package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.SegmentStore.PreparedSegment;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A segment store that can hold one publication open at a chosen point. */
final class PausingSegmentStore implements SegmentStore {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SegmentStore delegate;
  private final CountDownLatch paused = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);
  private final AtomicReference<PublicationPause> nextPause = new AtomicReference<>();

  enum PublicationPause {
    BEFORE_THE_SEGMENT_IS_READABLE,
    AFTER_THE_SEGMENT_IS_READABLE
  }

  PausingSegmentStore(SegmentStore delegate) {
    this.delegate = delegate;
  }

  void pauseNextPublication(PublicationPause pause) {
    nextPause.set(pause);
  }

  void awaitPausedPublication() throws InterruptedException {
    assertThat(paused.await(BOUND.toSeconds(), TimeUnit.SECONDS))
        .as("the upload reached its publication")
        .isTrue();
  }

  void releasePublication() {
    released.countDown();
  }

  @Override
  public byte[] readSegment(UUID sessionId, String segmentName) {
    return delegate.readSegment(sessionId, segmentName);
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    return delegate.segmentExists(sessionId, segmentName);
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
    var prepared = delegate.prepareSegment(sessionId, segmentName, data);
    var pause = nextPause.getAndSet(null);
    return new PreparedSegment() {
      @Override
      public SegmentPublication publish() {
        holdAt(pause, PublicationPause.BEFORE_THE_SEGMENT_IS_READABLE);
        var publication = prepared.publish();
        holdAt(pause, PublicationPause.AFTER_THE_SEGMENT_IS_READABLE);
        return publication;
      }

      @Override
      public void close() {
        prepared.close();
      }
    };
  }

  private void holdAt(PublicationPause pause, PublicationPause point) {
    if (pause != point) {
      return;
    }

    paused.countDown();
    try {
      if (!released.await(BOUND.toSeconds(), TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out holding the segment publication");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while holding the segment publication", e);
    }
  }

  @Override
  public void deleteSession(UUID sessionId) {
    delegate.deleteSession(sessionId);
  }
}
