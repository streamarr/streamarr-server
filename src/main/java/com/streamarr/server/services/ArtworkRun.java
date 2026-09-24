package com.streamarr.server.services;

import com.streamarr.server.exceptions.ArtworkResultNotSavedException;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The required artwork requested by one scan, refresh, or file discovery. The run completes once it
 * is closed to new requests and every registered request has finished or been withdrawn, so a
 * pending count that briefly reaches zero while discovery continues does not complete it. It
 * completes exceptionally with {@link ArtworkResultNotSavedException} when the database did not
 * store the results of any of its requests.
 */
public final class ArtworkRun implements AutoCloseable {

  private final String description;
  private final ImageRefreshMode imageRefreshMode;
  private final Clock clock;
  private final CompletableFuture<ArtworkRunSummary> completion = new CompletableFuture<>();

  private final List<Throwable> saveFailures = new ArrayList<>();

  private int pendingRequests;
  private ArtworkCounts counts = ArtworkCounts.NONE;
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

  /**
   * Completes with the run's summary once the run is closed and no request is pending, or fails
   * when the results of a request were not recorded.
   */
  public CompletableFuture<ArtworkRunSummary> completion() {
    return completion.copy();
  }

  /** Declares that the scan, refresh, or file discovery will request no more required artwork. */
  @Override
  public void close() {
    Optional<ArtworkRunSummary> summary;
    synchronized (this) {
      closed = true;
      summary = completedSummary();
    }

    summary.ifPresent(this::complete);
  }

  synchronized void register() {
    if (closed) {
      throw new IllegalStateException("Artwork run is closed to new requests: " + description);
    }

    if (firstRequestedAt == null) {
      firstRequestedAt = clock.instant();
    }

    pendingRequests++;
  }

  void finish(List<ArtworkResult> results) {
    Optional<ArtworkRunSummary> summary;
    synchronized (this) {
      summary = countFinished(results);
    }

    summary.ifPresent(this::complete);
  }

  /** Finishes a request whose results the database did not store. */
  void finishUnsaved(List<ArtworkResult> results, Throwable failure) {
    Optional<ArtworkRunSummary> summary;
    synchronized (this) {
      saveFailures.add(failure);
      summary = countFinished(results);
    }

    summary.ifPresent(this::complete);
  }

  private Optional<ArtworkRunSummary> countFinished(List<ArtworkResult> results) {
    pendingRequests--;
    counts = counts.plus(results);
    return completedSummary();
  }

  private void complete(ArtworkRunSummary summary) {
    List<Throwable> failures;
    synchronized (this) {
      failures = List.copyOf(saveFailures);
    }

    if (failures.isEmpty()) {
      completion.complete(summary);
      return;
    }

    completion.completeExceptionally(new ArtworkResultNotSavedException(description, failures));
  }

  private Optional<ArtworkRunSummary> completedSummary() {
    if (!closed || pendingRequests > 0) {
      return Optional.empty();
    }

    var elapsed = Duration.ZERO;
    if (firstRequestedAt != null) {
      elapsed = Duration.between(firstRequestedAt, clock.instant());
    }

    return Optional.of(
        ArtworkRunSummary.builder()
            .description(description)
            .counts(counts)
            .elapsed(elapsed)
            .build());
  }
}
