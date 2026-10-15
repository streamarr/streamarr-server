package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest;
import com.streamarr.server.services.ArtworkPlan.PlannedImage;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Clock;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Requests artwork for catalog targets. A request is stored in the transaction that records the
 * target's metadata, and scheduled artwork steps carry it out, so a committed request survives a
 * restart and its steps retry until each image has a result.
 */
@Service
@RequiredArgsConstructor
public class ArtworkRequestService {

  private final ArtworkRequestRepository requests;
  private final ArtworkScheduling scheduling;
  private final ImageService imageService;
  private final Clock clock;

  /** Opens a run that collects the primary artwork of one scan, refresh, or file discovery. */
  public ArtworkRun openRun(String description, ImageRefreshMode imageRefreshMode) {
    return new ArtworkRun(description, imageRefreshMode, clock);
  }

  /**
   * Requests a target's primary artwork for the run, in the caller's transaction. Stored images
   * that the run's refresh mode keeps count as skipped and need no request; the run waits for a
   * result of every other image. A rollback withdraws the request from the run.
   *
   * @throws IllegalStateException if the run is closed to new requests
   */
  @Transactional
  public void requestPrimaryArtwork(ArtworkRun run, ArtworkSources artwork) {
    var plan = plan(artwork, run.imageRefreshMode());
    var awaitedImageTypes =
        plan.images().stream()
            .filter(image -> !(image instanceof PlannedImage.KeepStoredImage))
            .map(PlannedImage::imageType)
            .collect(Collectors.toSet());
    var kept = plan.images().size() - awaitedImageTypes.size();
    run.countKeptImages(kept);
    afterRollback(() -> run.withdrawKeptImages(kept));
    if (awaitedImageTypes.isEmpty()) {
      return;
    }

    var saved = request(artwork, run.imageRefreshMode());
    var awaited =
        AwaitedArtwork.builder()
            .target(artwork.target())
            .generation(saved.generation())
            .awaitedImageTypes(awaitedImageTypes)
            .build();
    run.register(awaited);
    afterRollback(() -> run.withdraw(awaited));
  }

  /**
   * Requests a person's or company's artwork in the caller's transaction. Nothing waits for it, and
   * nothing is requested when the refresh mode keeps every stored image.
   */
  @Transactional
  public void requestBackgroundArtwork(ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var keepsEveryImage =
        plan(artwork, refreshMode).images().stream()
            .allMatch(PlannedImage.KeepStoredImage.class::isInstance);
    if (keepsEveryImage) {
      return;
    }

    request(artwork, refreshMode);
  }

  /** Returns how many background images are requested but not finished, server-wide. */
  public long pendingBackgroundImages() {
    return requests.findPendingArtworkByItemType().entrySet().stream()
        .filter(entry -> ArtworkPriority.of(entry.getKey()) == ArtworkPriority.BACKGROUND)
        .mapToLong(entry -> entry.getValue().images())
        .sum();
  }

  private ArtworkPlan plan(ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var storedImagesByType =
        imageService.findByEntity(artwork.targetId(), artwork.targetType()).stream()
            .collect(Collectors.groupingBy(Image::getImageType));
    return ArtworkPlan.of(artwork, storedImagesByType, refreshMode);
  }

  // A new generation is scheduled while this transaction holds the request's lock, so the request
  // and its scheduled task commit or roll back together.
  private SavedArtworkRequest request(ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var saved = requests.saveRequest(artwork, refreshMode, clock.instant());
    if (saved instanceof SavedArtworkRequest.NewGeneration) {
      scheduling.scheduleSteps(artwork.target());
    }

    return saved;
  }

  private static void afterRollback(Runnable onRollback) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              onRollback.run();
            }
          }
        });
  }
}
