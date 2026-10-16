package com.streamarr.server.services.probe;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Wakes the playback requests that wait for a media file's probe when an attempt at the probe ends.
 * The probe dispatcher runs on this server, so the attempt's end reaches the waiting requests in
 * process; each request then reads the stored result.
 */
@Component
public class ProbeAttemptEnds {

  private final ConcurrentMap<UUID, CountDownLatch> nextEnds = new ConcurrentHashMap<>();

  /**
   * Returns the next end of an attempt at the media file's probe. Obtain it before reading the
   * probe's stored state, so an attempt that ends after the read still ends the wait.
   */
  public NextEnd next(@NonNull UUID mediaFileId) {
    return new NextEnd(nextEnds.computeIfAbsent(mediaFileId, _ -> new CountDownLatch(1)));
  }

  public void signalEnd(@NonNull UUID mediaFileId) {
    var waiting = nextEnds.remove(mediaFileId);
    if (waiting != null) {
      waiting.countDown();
    }
  }

  @RequiredArgsConstructor
  public static final class NextEnd {

    private final CountDownLatch ended;

    /** Waits until the attempt ends or the timeout passes, and returns whether it ended. */
    public boolean await(@NonNull Duration timeout) throws InterruptedException {
      return ended.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
  }
}
