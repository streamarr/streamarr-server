package com.streamarr.server.services;

import com.streamarr.server.config.ArtworkRunProperties;
import com.streamarr.server.domain.media.ArtworkCompletionEvidence;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.services.concurrency.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Waits until the primary artwork that a library operation or one of its tasks requested has
 * recorded results. Artwork steps run in db-scheduler, so the wait reads recorded results rather
 * than waiting for an in-process signal, and a result recorded after a restart counts as well (ADR
 * 0027).
 */
@Slf4j
@Service
@Builder
@RequiredArgsConstructor
public class ArtworkRunWaiter {

  private final ArtworkRequestRepository requests;
  private final ItemResultRepository itemResults;
  private final Sleeper sleeper;
  private final Clock clock;
  private final ArtworkRunProperties properties;

  /**
   * Waits until each primary artwork request that carries the run's library operation id has a
   * result carrying that id for every image of the request's generation. The requests come from the
   * database, so an execution after a restart also waits for artwork that an earlier execution of
   * the operation requested. A failed result counts: its retry continues in the background. Counts
   * follow each image's own recorded outcome, plus the stored images that this run kept.
   *
   * @throws IllegalArgumentException if the run belongs to no library operation
   */
  public ArtworkRunSummary awaitLibraryOperationResults(ArtworkRun run)
      throws InterruptedException {
    var libraryOperationId =
        run.libraryOperationId()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Artwork run belongs to no library operation: " + run.description()));
    while (requests.countPrimaryImagesAwaitingResultForLibraryOperation(libraryOperationId) > 0) {
      sleeper.sleep(properties.resultCheckInterval());
    }

    var counts =
        FinishedImageCounts.NONE
            .plusSkipped(run.keptImages())
            .plus(itemResults.countArtworkOutcomesForLibraryOperation(libraryOperationId));
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
   * Tries to wait until each image type that one closed task run awaits has a result of the awaited
   * generation or a newer one, checking often, because an admitted task holds its admission while
   * it waits. A task of a library operation counts only results that carry the operation's id. A
   * target that was deleted, or whose newer request no longer asks for an awaited type, no longer
   * holds the task. A task that awaits no artwork returns at once without reading any result.
   *
   * <p>A failed read stops the task's wait instead of failing the task. The operation waits for the
   * same artwork through {@link #awaitLibraryOperationResults} when its walk ends.
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

  private void awaitFinishedArtwork(ArtworkRun run, Duration checkInterval)
      throws InterruptedException {
    var pending = new HashMap<ArtworkTarget, AwaitedArtwork>();
    run.awaited()
        .forEach(artwork -> pending.merge(artwork.target(), artwork, ArtworkRunWaiter::merge));
    while (!pending.isEmpty()) {
      removeFinishedArtwork(pending);
      if (!pending.isEmpty()) {
        sleeper.sleep(checkInterval);
      }
    }
  }

  // Removes each target whose awaited artwork is finished from pending.
  private void removeFinishedArtwork(Map<ArtworkTarget, AwaitedArtwork> pending) {
    for (var evidence : requests.findCompletionEvidence(pending.keySet())) {
      if (isFinished(evidence, pending.get(evidence.target()))) {
        pending.remove(evidence.target());
      }
    }
  }

  // Awaited artwork is finished when each awaited type has a result, or when no request remains.
  private static boolean isFinished(ArtworkCompletionEvidence evidence, AwaitedArtwork awaited) {
    if (evidence.pendingGeneration().isEmpty()) {
      return true;
    }

    var recordedTypes =
        evidence.artworkResults().stream()
            .filter(result -> result.artworkGeneration() >= awaited.generation())
            .filter(result -> isForTheAwaitingLibraryOperation(result, awaited))
            .map(ItemResult::imageType)
            .collect(Collectors.toSet());
    return recordedTypes.containsAll(awaited.awaitedImageTypes());
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
