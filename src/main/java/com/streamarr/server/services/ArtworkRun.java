package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The primary artwork requested by one scan, refresh, or file discovery. The run collects what it
 * requested until it is closed; {@link ArtworkRunWaiter#awaitResults} then waits for the recorded
 * results, so a result recorded after a restart counts as well.
 */
public final class ArtworkRun implements AutoCloseable {

  private final String description;
  private final ImageRefreshMode imageRefreshMode;
  private final Clock clock;

  private final List<AwaitedArtwork> awaited = new ArrayList<>();
  private int keptImages;
  private Instant firstRequestedAt;
  private boolean closed;

  ArtworkRun(String description, ImageRefreshMode imageRefreshMode, Clock clock) {
    this.description = description;
    this.imageRefreshMode = imageRefreshMode;
    this.clock = clock;
  }

  public ImageRefreshMode imageRefreshMode() {
    return imageRefreshMode;
  }

  public String description() {
    return description;
  }

  /** Declares that the scan, refresh, or file discovery will request no more primary artwork. */
  @Override
  public synchronized void close() {
    closed = true;
  }

  synchronized List<AwaitedArtwork> awaited() {
    return List.copyOf(awaited);
  }

  synchronized int keptImages() {
    return keptImages;
  }

  synchronized Optional<Instant> firstRequestedAt() {
    return Optional.ofNullable(firstRequestedAt);
  }

  /**
   * Adds a target's awaited artwork to the run.
   *
   * @throws IllegalStateException if the run is closed to new requests
   */
  synchronized void register(AwaitedArtwork artwork) {
    startRequest();
    awaited.add(artwork);
  }

  /**
   * Counts stored images that the run keeps without a request.
   *
   * @throws IllegalStateException if the run is closed to new requests
   */
  synchronized void countKeptImages(int kept) {
    startRequest();
    keptImages += kept;
  }

  /** Removes artwork whose request rolled back with the transaction that made it. */
  synchronized void withdraw(AwaitedArtwork artwork) {
    awaited.remove(artwork);
  }

  /** Removes kept images whose request rolled back with the transaction that made it. */
  synchronized void withdrawKeptImages(int kept) {
    keptImages -= kept;
  }

  private void startRequest() {
    if (closed) {
      throw new IllegalStateException("Artwork run is closed to new requests: " + description);
    }

    if (firstRequestedAt == null) {
      firstRequestedAt = clock.instant();
    }
  }
}
