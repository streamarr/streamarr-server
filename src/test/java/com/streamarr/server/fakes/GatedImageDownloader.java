package com.streamarr.server.fakes;

import com.streamarr.server.services.metadata.TmdbImageDownloader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Serves one image for every path; downloads of held paths block until the test releases them. */
public final class GatedImageDownloader implements TmdbImageDownloader {

  private final byte[] imageData;
  private final Set<String> heldPrefixes = ConcurrentHashMap.newKeySet();
  private final Set<String> startedPaths = ConcurrentHashMap.newKeySet();
  private final CountDownLatch releaseHeldDownloads = new CountDownLatch(1);
  private final AtomicInteger heldDownloads = new AtomicInteger();

  public GatedImageDownloader(byte[] imageData) {
    this.imageData = imageData;
  }

  public void holdPathsStartingWith(String prefix) {
    heldPrefixes.add(prefix);
  }

  public void releaseHeldDownloads() {
    releaseHeldDownloads.countDown();
  }

  public int heldDownloads() {
    return heldDownloads.get();
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
    heldDownloads.incrementAndGet();
    try {
      releaseHeldDownloads.await();
    } finally {
      heldDownloads.decrementAndGet();
    }
  }
}
