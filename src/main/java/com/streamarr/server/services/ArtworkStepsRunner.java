package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.exceptions.ObsoleteArtworkStepsAttemptException;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.services.ArtworkPlan.PlannedImage.DownloadImage;
import com.streamarr.server.services.ArtworkPlan.PlannedImage.KeepStoredImage;
import com.streamarr.server.services.ArtworkPlan.PlannedImage.ReportUnavailable;
import com.streamarr.server.services.ImageService.ProcessedImage;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.concurrency.CpuPermits;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs the artwork steps of one target for the current generation of its artwork request: fetch
 * each requested image, keep stored images that the refresh mode keeps, and report images that the
 * provider does not have. A step whose result of this generation is already final does not run
 * again, so repeating an attempt is safe.
 */
@Slf4j
@Component
@Builder
@RequiredArgsConstructor
public class ArtworkStepsRunner {

  private final ArtworkRequestRepository requests;
  private final ItemResultRepository itemResults;
  private final ImageService imageService;
  private final TmdbImageDownloader imageDownloader;
  private final CpuPermits cpuPermits;
  private final Clock clock;

  /** What one step left to do after its attempt. */
  private sealed interface StepOutcome {

    /** The image and its result were stored together. */
    record Saved() implements StepOutcome {}

    /** The step's result is still to be stored; a retryable failure may name a delay. */
    record ResultToStore(ItemResult result, Optional<Duration> retryAfter) implements StepOutcome {

      private boolean isRetryable() {
        return !result.isFinal();
      }
    }

    /** A newer generation replaced the attempt's, or the target was deleted. */
    record Obsolete() implements StepOutcome {}

    record Interrupted() implements StepOutcome {}
  }

  private record ImageDownload(DownloadImage planned, Future<ProcessedImage> processedImage) {}

  /**
   * Runs the steps of the target's pending artwork request that have no final result of its
   * generation yet, and stores each step's result.
   */
  public ArtworkStepsOutcome runSteps(ArtworkTarget target) {
    var pending = requests.findRequest(target);
    if (pending.isEmpty()) {
      return new ArtworkStepsOutcome.NotRequested();
    }

    var request = pending.get();
    var attempt =
        ArtworkStepsAttempt.builder()
            .target(target)
            .generation(request.generation())
            .attemptedAt(clock.instant())
            .libraryOperationId(request.libraryOperationId())
            .build();
    var storedImagesByType =
        imageService.findByEntity(target.targetId(), target.targetType()).stream()
            .collect(Collectors.groupingBy(Image::getImageType));
    var finishedTypes = finishedImageTypes(attempt);
    var planned =
        ArtworkPlan.of(request.artwork(), storedImagesByType, request.refreshMode())
            .images()
            .stream()
            .filter(image -> !finishedTypes.contains(image.imageType()))
            .toList();

    var outcomes = new ArrayList<StepOutcome>();
    var downloads = new ArrayList<DownloadImage>();
    for (var image : planned) {
      switch (image) {
        case KeepStoredImage(var imageType) ->
            outcomes.add(
                storeLater(
                    result(attempt, imageType, new ItemOutcome.Succeeded())
                        .sourceKey(storedKey(storedImagesByType, imageType))
                        .build()));
        case ReportUnavailable(var imageType) ->
            outcomes.add(
                storeLater(result(attempt, imageType, new ItemOutcome.Unavailable()).build()));
        case DownloadImage download -> downloads.add(download);
      }
    }

    outcomes.addAll(downloadAll(attempt, downloads));
    return finish(attempt, outcomes);
  }

  // A result of this generation or a newer one that no retry would change.
  private Set<ImageType> finishedImageTypes(ArtworkStepsAttempt attempt) {
    return itemResults
        .findByItem(attempt.target().targetId(), attempt.target().targetType())
        .stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .filter(result -> result.artworkGeneration() >= attempt.generation())
        .filter(ItemResult::isFinal)
        .map(ItemResult::imageType)
        .collect(Collectors.toSet());
  }

  private List<StepOutcome> downloadAll(ArtworkStepsAttempt attempt, List<DownloadImage> planned) {
    if (planned.isEmpty()) {
      return List.of();
    }

    var downloads = new ArrayList<ImageDownload>();
    try (var executor = SystemWork.newExecutorRunningAsSystemActor("artwork-download-")) {
      for (var image : planned) {
        downloads.add(
            new ImageDownload(image, executor.submit(() -> downloadAndProcess(attempt, image))));
      }
    }

    return downloads.stream().map(download -> save(attempt, download)).toList();
  }

  private ProcessedImage downloadAndProcess(ArtworkStepsAttempt attempt, DownloadImage planned)
      throws IOException, InterruptedException {
    var source = planned.source();
    var imageData =
        switch (source) {
          case TmdbImageSource tmdb -> imageDownloader.downloadImage(tmdb.key());
        };

    return cpuPermits.runWithPermit(
        cpuPriorityOf(attempt.target()),
        () ->
            imageService.processImage(
                imageData,
                source.imageType(),
                attempt.target().targetId(),
                attempt.target().targetType(),
                source.key()));
  }

  private static CpuPermits.Priority cpuPriorityOf(ArtworkTarget target) {
    return switch (ArtworkPriority.of(target.targetType())) {
      case PRIMARY -> CpuPermits.Priority.PRIMARY;
      case BACKGROUND -> CpuPermits.Priority.BACKGROUND;
    };
  }

  private StepOutcome save(ArtworkStepsAttempt attempt, ImageDownload download) {
    var planned = download.planned();
    if (download.processedImage().state() == Future.State.FAILED) {
      return failed(attempt, planned, download.processedImage().exceptionNow());
    }

    var processed = download.processedImage().resultNow();
    try {
      storeImages(attempt, planned, processed);
      return new StepOutcome.Saved();
    } catch (ObsoleteArtworkStepsAttemptException _) {
      imageService.deleteFiles(processed.writtenFiles());
      return new StepOutcome.Obsolete();
    } catch (RuntimeException e) {
      imageService.deleteFiles(processed.writtenFiles());
      return failed(attempt, planned, e);
    }
  }

  private void storeImages(
      ArtworkStepsAttempt attempt, DownloadImage planned, ProcessedImage processed) {
    if (planned.replacesStoredImage()) {
      imageService.replaceImages(processed, attempt);
      return;
    }

    imageService.saveImages(processed.images(), attempt);
  }

  private StepOutcome failed(ArtworkStepsAttempt attempt, DownloadImage planned, Throwable cause) {
    if (cause instanceof InterruptedException) {
      return new StepOutcome.Interrupted();
    }

    var failure = ArtworkFailure.classify(cause);
    log.warn(
        "Artwork step {} failed for {} {}: {} ({})",
        planned.imageType(),
        attempt.target().targetType(),
        attempt.target().targetId(),
        failure.reason(),
        cause.toString());
    var retryAfter =
        switch (failure) {
          case ArtworkFailure.Retryable(var after) -> after;
          case ArtworkFailure.Terminal _ -> Optional.<Duration>empty();
        };
    return new StepOutcome.ResultToStore(
        result(attempt, planned.imageType(), ItemOutcome.Failed.of(failure.reason(), cause))
            .sourceKey(planned.source().key())
            .build(),
        retryAfter);
  }

  private ArtworkStepsOutcome finish(ArtworkStepsAttempt attempt, List<StepOutcome> outcomes) {
    // An interrupted attempt clears the flag here, so its results and its completion can still be
    // stored; the scheduled task then runs again without counting a failure.
    var interrupted =
        Thread.interrupted()
            || outcomes.stream().anyMatch(StepOutcome.Interrupted.class::isInstance);
    if (outcomes.stream().anyMatch(StepOutcome.Obsolete.class::isInstance)) {
      return new ArtworkStepsOutcome.Finished(attempt.generation());
    }

    var unsaved =
        outcomes.stream()
            .filter(StepOutcome.ResultToStore.class::isInstance)
            .map(StepOutcome.ResultToStore.class::cast)
            .toList();
    return switch (storeResults(attempt, unsaved)) {
      case OBSOLETE -> new ArtworkStepsOutcome.Finished(attempt.generation());
      case NOT_STORED ->
          new ArtworkStepsOutcome.Unfinished(
              attempt.generation(), attempt.libraryOperationId(), Optional.empty());
      case STORED -> outcomeOfStoredAttempt(attempt, unsaved, interrupted);
    };
  }

  // Each step's result is stored; what remains depends on the outcomes.
  private static ArtworkStepsOutcome outcomeOfStoredAttempt(
      ArtworkStepsAttempt attempt, List<StepOutcome.ResultToStore> stored, boolean interrupted) {
    if (interrupted) {
      return new ArtworkStepsOutcome.Interrupted(attempt.generation());
    }

    var retryable = stored.stream().filter(StepOutcome.ResultToStore::isRetryable).toList();
    if (retryable.isEmpty()) {
      return new ArtworkStepsOutcome.Finished(attempt.generation());
    }

    var retryAfter =
        retryable.stream()
            .map(StepOutcome.ResultToStore::retryAfter)
            .flatMap(Optional::stream)
            .max(Duration::compareTo);
    return new ArtworkStepsOutcome.Unfinished(
        attempt.generation(), attempt.libraryOperationId(), retryAfter);
  }

  private enum ResultStorage {
    STORED,
    OBSOLETE,
    NOT_STORED
  }

  // Failed, unavailable and kept results are stored in one transaction, so one failed write leaves
  // every one of them pending rather than some stored and some lost.
  private ResultStorage storeResults(
      ArtworkStepsAttempt attempt, List<StepOutcome.ResultToStore> unsaved) {
    if (unsaved.isEmpty()) {
      return ResultStorage.STORED;
    }

    try {
      var results = unsaved.stream().map(StepOutcome.ResultToStore::result).toList();
      if (!itemResults.trySaveArtworkResults(attempt, results)) {
        return ResultStorage.OBSOLETE;
      }

      return ResultStorage.STORED;
    } catch (RuntimeException e) {
      log.error(
          "Failed to store artwork results for {} {}; the steps stay pending",
          attempt.target().targetType(),
          attempt.target().targetId(),
          e);
      return ResultStorage.NOT_STORED;
    }
  }

  private static StepOutcome storeLater(ItemResult result) {
    return new StepOutcome.ResultToStore(result, Optional.empty());
  }

  private static String storedKey(Map<ImageType, List<Image>> storedImagesByType, ImageType type) {
    var stored = storedImagesByType.getOrDefault(type, List.of());
    if (stored.isEmpty()) {
      return null;
    }

    return stored.getFirst().getKey();
  }

  private static ItemResult.ItemResultBuilder result(
      ArtworkStepsAttempt attempt, ImageType imageType, ItemOutcome outcome) {
    return ItemResult.builder()
        .itemId(attempt.target().targetId())
        .itemType(attempt.target().targetType())
        .step(ItemStep.ARTWORK)
        .imageType(imageType)
        .outcome(outcome)
        .attemptedAt(attempt.attemptedAt())
        .artworkGeneration(attempt.generation());
  }
}
