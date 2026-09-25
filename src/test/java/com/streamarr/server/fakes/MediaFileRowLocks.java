package com.streamarr.server.fakes;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;

/**
 * Stands in for the media file row lock that a probe write holds until its transaction ends. The
 * thread that holds a media file's lock may take it again; other writers for that file wait.
 */
final class MediaFileRowLocks {

  private final Map<UUID, Thread> owners = new HashMap<>();
  private final Map<UUID, Integer> holds = new HashMap<>();
  private final Map<UUID, Integer> waiting = new HashMap<>();

  <T> T holding(UUID mediaFileId, Supplier<T> writes) {
    lock(mediaFileId);
    try {
      return writes.get();
    } finally {
      unlock(mediaFileId);
    }
  }

  synchronized void awaitWaiting(UUID mediaFileId, int count, Duration bound)
      throws InterruptedException {
    var deadline = System.nanoTime() + bound.toNanos();
    while (waiting.getOrDefault(mediaFileId, 0) < count) {
      var remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new AssertionError(
            waiting.getOrDefault(mediaFileId, 0)
                + " of "
                + count
                + " writes waited within "
                + bound);
      }

      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
  }

  private synchronized void lock(UUID mediaFileId) {
    var current = Thread.currentThread();
    if (owners.get(mediaFileId) == current) {
      holds.merge(mediaFileId, 1, Integer::sum);
      return;
    }

    waiting.merge(mediaFileId, 1, Integer::sum);
    notifyAll();
    try {
      while (owners.containsKey(mediaFileId)) {
        wait();
      }
    } catch (InterruptedException _) {
      // An interrupted writer stops, as a cancelled statement would, and writes nothing.
      Thread.currentThread().interrupt();
      throw new CannotAcquireLockException("Interrupted while waiting for " + mediaFileId);
    } finally {
      waiting.merge(mediaFileId, -1, Integer::sum);
    }

    owners.put(mediaFileId, current);
    holds.put(mediaFileId, 1);
  }

  private synchronized void unlock(UUID mediaFileId) {
    if (holds.merge(mediaFileId, -1, Integer::sum) > 0) {
      return;
    }

    owners.remove(mediaFileId);
    holds.remove(mediaFileId);
    notifyAll();
  }
}
