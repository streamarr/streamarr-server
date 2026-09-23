package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ArtworkService {

  private final ArtworkFetcher artworkFetcher;
  private final Clock clock;
  private final ExecutorService requiredArtworkExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("required-artwork-", 0).factory());

  // The image client's rate limiter grants permits in arrival order, so an unbounded backlog of
  // person and company images would take permits ahead of required artwork. Bounding concurrent
  // secondary fetches keeps that backlog in this executor's queue instead.
  private final ExecutorService secondaryArtworkExecutor;

  public ArtworkService(
      ArtworkFetcher artworkFetcher,
      Clock clock,
      @Value("${artwork.secondary-concurrency:4}") int secondaryConcurrency) {
    this.artworkFetcher = artworkFetcher;
    this.clock = clock;
    this.secondaryArtworkExecutor =
        Executors.newFixedThreadPool(
            secondaryConcurrency, Thread.ofVirtual().name("secondary-artwork-", 0).factory());
  }

  /** Opens a run that collects the required artwork of one scan, refresh, or file discovery. */
  public ArtworkRun openRun(String description, ImageRefreshMode imageRefreshMode) {
    return new ArtworkRun(description, imageRefreshMode, clock);
  }

  /**
   * Fetches an entity's required artwork for the run. The request is registered with the run before
   * this method returns. Inside a transaction the fetch starts after commit, and a rollback
   * withdraws the request and completes the future with no results. Otherwise the future completes
   * once every requested source image is saved, skipped, unavailable, or failed.
   *
   * @throws IllegalStateException if the run is closed to new requests
   */
  public CompletableFuture<List<ArtworkResult>> fetchRequired(
      ArtworkRun run, ArtworkSources artwork) {
    run.register();
    var request = new CompletableFuture<List<ArtworkResult>>();
    afterOwningTransaction(
        () -> startRequiredFetch(run, artwork, request), () -> finish(run, request, List.of()));
    return request;
  }

  /**
   * Fetches person and company artwork in the background. At most {@code
   * artwork.secondary-concurrency} secondary fetches run at once, and the rest wait in a queue so
   * they cannot delay required artwork.
   */
  public CompletableFuture<List<ArtworkResult>> fetchSecondary(
      ArtworkSources artwork, ImageRefreshMode refreshMode) {
    try {
      return CompletableFuture.supplyAsync(
          () -> artworkFetcher.fetch(artwork, refreshMode), secondaryArtworkExecutor);
    } catch (RejectedExecutionException e) {
      return CompletableFuture.completedFuture(artwork.failures(e));
    }
  }

  @PreDestroy
  public void shutdown() {
    requiredArtworkExecutor.shutdownNow();
    secondaryArtworkExecutor.shutdownNow();
  }

  private void startRequiredFetch(
      ArtworkRun run, ArtworkSources artwork, CompletableFuture<List<ArtworkResult>> request) {
    try {
      requiredArtworkExecutor.execute(
          () -> finish(run, request, artworkFetcher.fetch(artwork, run.imageRefreshMode())));
    } catch (RejectedExecutionException e) {
      finish(run, request, artwork.failures(e));
    }
  }

  private static void finish(
      ArtworkRun run, CompletableFuture<List<ArtworkResult>> request, List<ArtworkResult> results) {
    run.finish(results);
    request.complete(results);
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
