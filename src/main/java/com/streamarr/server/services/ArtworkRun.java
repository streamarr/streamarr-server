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
 *
 * <p>A task run collects what one admitted task of the operation requests. Everything it collects
 * or withdraws is also collected or withdrawn by the operation's run, so the operation still counts
 * all of its artwork, and {@link ArtworkRunWaiter#tryAwaitTaskResults} waits for the task's artwork
 * alone.
 */
public final class ArtworkRun implements AutoCloseable {

  private final String description;
  private final ImageRefreshMode imageRefreshMode;
  private final Clock clock;
  private final Optional<ArtworkRun> operationRun;

  private final List<AwaitedArtwork> awaited = new ArrayList<>();
  private int keptImages;
  private Instant firstRequestedAt;
  private boolean closed;

  ArtworkRun(String description, ImageRefreshMode imageRefreshMode, Clock clock) {
    this.description = description;
    this.imageRefreshMode = imageRefreshMode;
    this.clock = clock;
    this.operationRun = Optional.empty();
  }

  private ArtworkRun(ArtworkRun operationRun) {
    this.description = operationRun.description;
    this.imageRefreshMode = operationRun.imageRefreshMode;
    this.clock = operationRun.clock;
    this.operationRun = Optional.of(operationRun);
    // A task run starts empty and open, whatever its operation's run holds.
    this.keptImages = 0;
    this.firstRequestedAt = null;
    this.closed = false;
  }

  /**
   * Opens a run for one task of this operation, with the same refresh mode. The task closes it when
   * it will request no more artwork; this run stays open.
   */
  public ArtworkRun openTaskRun() {
    return new ArtworkRun(this);
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
    operationRun.ifPresent(run -> run.register(artwork));
  }

  /**
   * Counts stored images that the run keeps without a request.
   *
   * @throws IllegalStateException if the run is closed to new requests
   */
  synchronized void countKeptImages(int kept) {
    startRequest();
    keptImages += kept;
    operationRun.ifPresent(run -> run.countKeptImages(kept));
  }

  /** Removes artwork whose request rolled back with the transaction that made it. */
  synchronized void withdraw(AwaitedArtwork artwork) {
    awaited.remove(artwork);
    operationRun.ifPresent(run -> run.withdraw(artwork));
  }

  /** Removes kept images whose request rolled back with the transaction that made it. */
  synchronized void withdrawKeptImages(int kept) {
    keptImages -= kept;
    operationRun.ifPresent(run -> run.withdrawKeptImages(kept));
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
