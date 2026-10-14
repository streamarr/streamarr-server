package com.streamarr.server.fakes;

import com.streamarr.server.services.metadata.TmdbImageDownloader;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Serves one image for every path; downloads of held paths block until the test releases them. */
public final class GatedImageDownloader implements TmdbImageDownloader {

  private final byte[] imageData;
  private final Set<String> heldPrefixes = ConcurrentHashMap.newKeySet();
  private final Set<String> startedPaths = ConcurrentHashMap.newKeySet();
  private final CountDownLatch releaseHeldDownloads = new CountDownLatch(1);
  private int heldDownloads;

  public GatedImageDownloader(byte[] imageData) {
    this.imageData = imageData;
  }

  public void holdPathsStartingWith(String prefix) {
    heldPrefixes.add(prefix);
  }

  public void releaseHeldDownloads() {
    releaseHeldDownloads.countDown();
  }

  public synchronized int heldDownloads() {
    return heldDownloads;
  }

  /** Waits until at least {@code count} downloads are held, failing after {@code bound}. */
  public synchronized void awaitHeldDownloads(int count, Duration bound)
      throws InterruptedException {
    var deadline = System.nanoTime() + bound.toNanos();
    while (heldDownloads < count) {
      var remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new AssertionError(
            heldDownloads + " of " + count + " downloads were held within " + bound);
      }

      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
  }

  public boolean hasStarted(String path) {
    return startedPaths.contains(path);
  }

  @Override
  public byte[] downloadImage(String pathFragment) throws InterruptedException {
    startedPaths.add(pathFragment);
    if (isHeld(pathFragment)) {
      awaitRelease();
    }

    return imageData;
  }

  private boolean isHeld(String pathFragment) {
    return heldPrefixes.stream().anyMatch(pathFragment::startsWith);
  }

  private void awaitRelease() throws InterruptedException {
    changeHeldDownloads(1);
    try {
      releaseHeldDownloads.await();
    } finally {
      changeHeldDownloads(-1);
    }
  }

  private synchronized void changeHeldDownloads(int change) {
    heldDownloads += change;
    notifyAll();
  }
}
