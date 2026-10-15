package com.streamarr.server.services;

import com.streamarr.server.config.ArtworkRunProperties;
import com.streamarr.server.domain.media.ArtworkCompletionEvidence;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.concurrency.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Waits until the primary artwork that a run requested has recorded results. Artwork steps run in
 * db-scheduler, so the wait reads recorded results rather than waiting for an in-process signal,
 * and a result recorded after a restart counts as well (ADR 0027).
 */
@Slf4j
@Service
@Builder
@RequiredArgsConstructor
public class ArtworkRunWaiter {

  private final ArtworkRequestRepository requests;
  private final Sleeper sleeper;
  private final Clock clock;
  private final ArtworkRunProperties properties;

  /**
   * Waits until each image type that the closed run awaits has a result of the awaited generation
   * or a newer one. A failed result counts: its retry continues in the background. A target that
   * was deleted, or whose newer request no longer asks for an awaited type, no longer holds the
   * run. Counts follow each image's own recorded outcome.
   */
  public ArtworkRunSummary awaitResults(ArtworkRun run) throws InterruptedException {
    var counts = awaitFinishedArtwork(run, properties.resultCheckInterval());
    var elapsed =
        run.firstRequestedAt()
            .map(requestedAt -> Duration.between(requestedAt, clock.instant()))
            .orElse(Duration.ZERO);
    var summary =
        ArtworkRunSummary.builder()
            .description(run.description())
            .counts(counts)
            .elapsed(elapsed)
            .build();
    logCompleted(summary);
    return summary;
  }

  /**
   * Tries to wait as {@link #awaitResults} does for the artwork that one closed task run awaits,
   * and checks more often, because an admitted task holds its admission while it waits. A task that
   * awaits no artwork returns at once without reading any result.
   *
   * <p>A failed read stops the task's wait instead of failing the task. The operation's run holds
   * the same artwork, and {@link #awaitResults} reads its results again when the operation ends.
   *
   * @return true when the task's artwork has results; false when a read of the results failed
   */
  public boolean tryAwaitTaskResults(ArtworkRun taskRun) throws InterruptedException {
    try {
      awaitFinishedArtwork(taskRun, properties.taskResultCheckInterval());
      return true;
    } catch (DataAccessException failure) {
      log.warn(
          "Stopped waiting for one task's primary artwork in the {}, which waits for it at its end.",
          taskRun.description(),
          failure);
      return false;
    }
  }

  private FinishedImageCounts awaitFinishedArtwork(ArtworkRun run, Duration checkInterval)
      throws InterruptedException {
    var pending = new HashMap<ArtworkTarget, AwaitedArtwork>();
    run.awaited()
        .forEach(artwork -> pending.merge(artwork.target(), artwork, ArtworkRunWaiter::merge));
    var counts = FinishedImageCounts.NONE.plusSkipped(run.keptImages());
    while (!pending.isEmpty()) {
      counts = countFinishedArtwork(pending, counts);
      if (!pending.isEmpty()) {
        sleeper.sleep(checkInterval);
      }
    }

    return counts;
  }

  // Removes each target whose awaited artwork is finished from pending, and counts its results.
  private FinishedImageCounts countFinishedArtwork(
      Map<ArtworkTarget, AwaitedArtwork> pending, FinishedImageCounts counts) {
    var total = counts;
    for (var evidence : requests.findCompletionEvidence(pending.keySet())) {
      var finished = finishedResults(evidence, pending.get(evidence.target()));
      if (finished.isEmpty()) {
        continue;
      }

      pending.remove(evidence.target());
      for (var result : finished.get()) {
        total = total.plus(result.outcome());
      }
    }

    return total;
  }

  // Returns the results that finish the awaited artwork, or nothing while it is still pending.
  private static Optional<List<ItemResult>> finishedResults(
      ArtworkCompletionEvidence evidence, AwaitedArtwork awaited) {
    var resultsByType = new EnumMap<ImageType, ItemResult>(ImageType.class);
    evidence.artworkResults().stream()
        .filter(result -> result.artworkGeneration() >= awaited.generation())
        .filter(result -> awaited.awaitedImageTypes().contains(result.imageType()))
        .filter(result -> isForTheAwaitingLibraryOperation(result, awaited))
        .forEach(result -> resultsByType.put(result.imageType(), result));
    if (resultsByType.keySet().containsAll(awaited.awaitedImageTypes())) {
      return Optional.of(List.copyOf(resultsByType.values()));
    }

    if (evidence.pendingGeneration().isPresent()) {
      return Optional.empty();
    }

    return Optional.of(List.copyOf(resultsByType.values()));
  }

  // A run of a library operation counts only results saved for the operation, so a result that an
  // earlier operation left does not release a task's admission.
  private static boolean isForTheAwaitingLibraryOperation(
      ItemResult result, AwaitedArtwork awaited) {
    return awaited.libraryOperationId().isEmpty()
        || awaited.libraryOperationId().equals(result.libraryOperationId());
  }

  private static AwaitedArtwork merge(AwaitedArtwork earlier, AwaitedArtwork later) {
    var awaitedImageTypes = new HashSet<>(earlier.awaitedImageTypes());
    awaitedImageTypes.addAll(later.awaitedImageTypes());
    return AwaitedArtwork.builder()
        .target(earlier.target())
        .generation(Math.max(earlier.generation(), later.generation()))
        .awaitedImageTypes(awaitedImageTypes)
        .libraryOperationId(later.libraryOperationId())
        .build();
  }

  private static void logCompleted(ArtworkRunSummary summary) {
    if (summary.counts().equals(FinishedImageCounts.NONE)) {
      return;
    }

    log.info(
        "Finished primary artwork for {} in {} seconds: {}.",
        summary.description(),
        summary.elapsed().toMillis() / 1000.0,
        summary.counts().describe());
  }
}
