package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class ArtworkService {

  private record RequiredFetch(
      ArtworkRun run, ArtworkSources artwork, CompletableFuture<List<ArtworkResult>> request) {

    private int sourceImages() {
      return artwork.requestedImageTypes().size();
    }
  }

  private final ArtworkFetcher artworkFetcher;
  private final ArtworkProgress progress;
  private final Clock clock;
  private final ExecutorService requiredArtworkExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("required-artwork-", 0).factory());

  // The image client's rate limiter grants permits in arrival order, so an unbounded backlog of
  // person and company images would take permits ahead of required artwork. Bounding concurrent
  // secondary fetches keeps that backlog in this executor's queue instead.
  private final ExecutorService secondaryArtworkExecutor;

  public ArtworkService(
      ArtworkFetcher artworkFetcher,
      ArtworkProgress progress,
      Clock clock,
      @Value("${artwork.secondary-concurrency:4}") int secondaryConcurrency) {
    this.artworkFetcher = artworkFetcher;
    this.progress = progress;
    this.clock = clock;
    this.secondaryArtworkExecutor =
        Executors.newFixedThreadPool(
            secondaryConcurrency, Thread.ofVirtual().name("secondary-artwork-", 0).factory());
  }

  /** Opens a run that collects the required artwork of one scan, refresh, or file discovery. */
  public ArtworkRun openRun(String description, ImageRefreshMode imageRefreshMode) {
    var run = new ArtworkRun(description, imageRefreshMode, clock);
    run.completion().thenAccept(progress::runCompleted);
    return run;
  }

  /**
   * Fetches an entity's required artwork for the run. The request is registered with the run before
   * this method returns. Inside a transaction the fetch starts after commit, and a rollback
   * withdraws the request and completes the future with no results. Otherwise the future completes
   * once every requested source image is saved, skipped, unavailable, or failed, and its result is
   * stored. If an unavailable or failed result cannot be stored, the future completes exceptionally
   * with the database error and the run fails once its other requests finish.
   *
   * @throws IllegalStateException if the run is closed to new requests
   */
  public CompletableFuture<List<ArtworkResult>> fetchRequired(
      ArtworkRun run, ArtworkSources artwork) {
    run.register();
    var request = new CompletableFuture<List<ArtworkResult>>();
    var fetch = new RequiredFetch(run, artwork, request);
    progress.requested(ArtworkPriority.REQUIRED, fetch.sourceImages());
    afterOwningTransaction(() -> startRequiredFetch(fetch), () -> finish(fetch, List.of()));
    return request;
  }

  /**
   * Fetches person and company artwork in the background. At most {@code
   * artwork.secondary-concurrency} secondary fetches run at once, and the rest wait in a queue so
   * they cannot delay required artwork.
   */
  public CompletableFuture<List<ArtworkResult>> fetchSecondary(
      ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var sourceImages = artwork.requestedImageTypes().size();
    progress.requested(ArtworkPriority.SECONDARY, sourceImages);
    var attemptedAt = clock.instant();
    CompletableFuture<List<ArtworkResult>> results;
    try {
      results =
          CompletableFuture.supplyAsync(
              () -> artworkFetcher.fetch(artwork, refreshMode, attemptedAt),
              secondaryArtworkExecutor);
    } catch (RejectedExecutionException e) {
      results = CompletableFuture.completedFuture(artwork.failures(e));
    }

    return results.whenComplete(
        (finished, failure) -> {
          var counted = finished;
          if (failure != null) {
            log.error(
                "Failed to record artwork results for entity {}", artwork.entityId(), failure);
            counted = artwork.failures(failure);
          }

          progress.finished(ArtworkPriority.SECONDARY, sourceImages, counted);
        });
  }

  @PreDestroy
  public void shutdown() {
    requiredArtworkExecutor.shutdownNow();
    secondaryArtworkExecutor.shutdownNow();
  }

  private void startRequiredFetch(RequiredFetch fetch) {
    // Stamped when the owning transaction commits, so attempts order like the metadata they follow.
    var attemptedAt = clock.instant();
    try {
      requiredArtworkExecutor.execute(() -> fetchRequiredArtwork(fetch, attemptedAt));
    } catch (RejectedExecutionException e) {
      finish(fetch, fetch.artwork().failures(e));
    }
  }

  private void fetchRequiredArtwork(RequiredFetch fetch, Instant attemptedAt) {
    List<ArtworkResult> results;
    try {
      results = artworkFetcher.fetch(fetch.artwork(), fetch.run().imageRefreshMode(), attemptedAt);
    } catch (RuntimeException e) {
      log.error("Failed to record artwork results for entity {}", fetch.artwork().entityId(), e);
      var failures = fetch.artwork().failures(e);
      fetch.run().finishUnrecorded(failures, e);
      progress.finished(ArtworkPriority.REQUIRED, fetch.sourceImages(), failures);
      fetch.request().completeExceptionally(e);
      return;
    }

    finish(fetch, results);
  }

  private void finish(RequiredFetch fetch, List<ArtworkResult> results) {
    fetch.run().finish(results);
    progress.finished(ArtworkPriority.REQUIRED, fetch.sourceImages(), results);
    fetch.request().complete(results);
  }

  private static void afterOwningTransaction(Runnable onCommit, Runnable onRollback) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      onCommit.run();
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
              onCommit.run();
              return;
            }

            onRollback.run();
          }
        });
  }
}
