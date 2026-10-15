package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest;
import com.streamarr.server.services.ArtworkPlan.PlannedImage;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
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

  /** Opens a run that collects the primary artwork of one file discovery. */
  public ArtworkRun openRun(String description, ImageRefreshMode imageRefreshMode) {
    return new ArtworkRun(description, imageRefreshMode, clock, Optional.empty());
  }

  /**
   * Opens a run that collects the primary artwork of one library operation. Each of its requests
   * carries the operation's id and takes over the target's pending request.
   */
  public ArtworkRun openLibraryOperationRun(
      String description, ImageRefreshMode imageRefreshMode, UUID libraryOperationId) {
    return new ArtworkRun(description, imageRefreshMode, clock, Optional.of(libraryOperationId));
  }

  /**
   * Requests a target's primary artwork for the run, in the caller's transaction. A target whose
   * stored images the run's refresh mode keeps needs no request, and its images count as skipped.
   * Otherwise the request covers every image type: the steps keep the stored images that the mode
   * keeps, which then count as saved, and the run waits for a result of every other image. A
   * rollback withdraws the request from the run.
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
    if (awaitedImageTypes.isEmpty()) {
      var kept = plan.images().size();
      run.countKeptImages(kept);
      afterRollback(() -> run.withdrawKeptImages(kept));
      return;
    }

    var saved = request(artwork, run.imageRefreshMode(), run.libraryOperationId());
    var awaited =
        AwaitedArtwork.builder()
            .target(artwork.target())
            .generation(saved.generation())
            .awaitedImageTypes(awaitedImageTypes)
            .libraryOperationId(run.libraryOperationId())
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

    request(artwork, refreshMode, Optional.empty());
  }

  /** Returns how many background images are requested but not finished, server-wide. */
  public long pendingBackgroundImages() {
    return requests.findArtworkBacklogByTargetType().entrySet().stream()
        .filter(entry -> ArtworkPriority.of(entry.getKey()) == ArtworkPriority.BACKGROUND)
        .mapToLong(entry -> entry.getValue().pendingImages())
        .sum();
  }

  private ArtworkPlan plan(ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var storedImagesByType =
        imageService.findByEntity(artwork.targetId(), artwork.targetType()).stream()
            .collect(Collectors.groupingBy(Image::getImageType));
    return ArtworkPlan.of(artwork, storedImagesByType, refreshMode);
  }

  // A new generation, and a request that a library operation took over, are scheduled while this
  // transaction holds the request's lock, so the request and its scheduled task commit or roll back
  // together. A taken-over request that waits to retry runs at once.
  private SavedArtworkRequest request(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Optional<UUID> libraryOperationId) {
    var saved =
        requests.saveRequest(
            NewArtworkRequest.builder()
                .artwork(artwork)
                .refreshMode(refreshMode)
                .requestedAt(clock.instant())
                .libraryOperationId(libraryOperationId)
                .build());
    if (!(saved instanceof SavedArtworkRequest.Unchanged)) {
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
