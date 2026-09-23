package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.services.ArtworkResult.Failed;
import com.streamarr.server.services.ArtworkResult.Saved;
import com.streamarr.server.services.ArtworkResult.Skipped;
import com.streamarr.server.services.ArtworkResult.Unavailable;
import com.streamarr.server.services.ImageService.ProcessedImage;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ArtworkFetcher {

  private record PendingImageSource(ImageSource source, boolean replacement) {}

  private record ImageDownload(
      PendingImageSource pendingSource, Future<ProcessedImage> processedImage) {}

  private final TmdbImageDownloader tmdbImageDownloader;
  private final ImageService imageService;
  private final ItemResultRepository itemResults;
  private final MutexFactory<String> mutexFactory;

  public ArtworkFetcher(
      TmdbImageDownloader tmdbImageDownloader,
      ImageService imageService,
      ItemResultRepository itemResults,
      MutexFactoryProvider mutexFactoryProvider) {
    this.tmdbImageDownloader = tmdbImageDownloader;
    this.imageService = imageService;
    this.itemResults = itemResults;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  /**
   * Returns one result per requested source image; an image that cannot be fetched is a result, not
   * an exception. Each result is stored as the entity's artwork result: a saved image in the
   * transaction that stores it, and an unavailable or failed image afterwards.
   *
   * @param attemptedAt when this attempt started; a stored result from a later attempt is kept
   * @throws org.springframework.dao.DataAccessException if a result cannot be stored
   */
  public List<ArtworkResult> fetch(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant attemptedAt) {
    var mutex = mutexFactory.getMutex(artwork.entityId().toString());

    try {
      mutex.lockInterruptibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Image enrichment interrupted for entity {}", artwork.entityId());
      return artwork.failures(e);
    }

    try {
      var results = fetchOrReportFailures(artwork, refreshMode, attemptedAt);
      recordUnsavedResults(artwork, results, attemptedAt);
      return results;
    } finally {
      mutex.unlock();
    }
  }

  private List<ArtworkResult> fetchOrReportFailures(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant attemptedAt) {
    try {
      return fetchWhileLocked(artwork, refreshMode, attemptedAt);
    } catch (RuntimeException e) {
      log.error(
          "Failed to fetch images for entity {} ({})", artwork.entityId(), artwork.entityType(), e);
      return artwork.failures(e);
    }
  }

  private List<ArtworkResult> fetchWhileLocked(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant attemptedAt) {
    var existingImagesByType =
        imageService.findByEntity(artwork.entityId(), artwork.entityType()).stream()
            .collect(Collectors.groupingBy(Image::getImageType));
    var results = new ArrayList<ArtworkResult>();
    for (var imageType : artwork.missingImageTypes()) {
      results.add(
          resultWithoutSource(imageType, existingImagesByType.containsKey(imageType), refreshMode));
    }

    var pendingSources = new ArrayList<PendingImageSource>();
    for (var source : artwork.sources()) {
      var existingForType = existingImagesByType.getOrDefault(source.imageType(), List.of());
      if (!requiresDownload(source, existingForType, refreshMode)) {
        results.add(new Skipped(source.imageType()));
        continue;
      }

      pendingSources.add(new PendingImageSource(source, !existingForType.isEmpty()));
    }

    if (pendingSources.isEmpty()) {
      log.debug(
          "All image types already exist for entity {} ({}), skipping",
          artwork.entityId(),
          artwork.entityType());
      return results;
    }

    results.addAll(downloadAllImages(artwork, pendingSources, attemptedAt));
    return results;
  }

  private static ArtworkResult resultWithoutSource(
      ImageType imageType, boolean stored, ImageRefreshMode refreshMode) {
    if (stored && refreshMode == ImageRefreshMode.PRESERVE) {
      return new Skipped(imageType);
    }

    return new Unavailable(imageType);
  }

  private boolean requiresDownload(
      ImageSource source, List<Image> existingImages, ImageRefreshMode refreshMode) {
    if (existingImages.isEmpty()) {
      return true;
    }

    return switch (refreshMode) {
      case PRESERVE -> false;
      case REFRESH_IF_CHANGED ->
          existingImages.stream().anyMatch(image -> !Objects.equals(image.getKey(), source.key()));
      case FORCE_REFRESH -> true;
    };
  }

  private List<ArtworkResult> downloadAllImages(
      ArtworkSources artwork, List<PendingImageSource> pendingSources, Instant attemptedAt) {
    var downloads = new ArrayList<ImageDownload>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var pendingSource : pendingSources) {
        downloads.add(
            new ImageDownload(
                pendingSource,
                executor.submit(() -> downloadAndProcessImage(pendingSource, artwork))));
      }
    }

    return downloads.stream()
        .map(download -> saveDownloadedImage(artwork, download, attemptedAt))
        .toList();
  }

  private ProcessedImage downloadAndProcessImage(
      PendingImageSource pendingSource, ArtworkSources artwork)
      throws IOException, InterruptedException {
    var source = pendingSource.source();
    var imageData =
        switch (source) {
          case TmdbImageSource tmdb -> tmdbImageDownloader.downloadImage(tmdb.key());
        };

    return imageService.processImage(
        imageData, source.imageType(), artwork.entityId(), artwork.entityType(), source.key());
  }

  private ArtworkResult saveDownloadedImage(
      ArtworkSources artwork, ImageDownload download, Instant attemptedAt) {
    var imageType = download.pendingSource().source().imageType();
    if (download.processedImage().state() == Future.State.FAILED) {
      return failedDownload(artwork, imageType, download.processedImage().exceptionNow());
    }

    var processedImage = download.processedImage().resultNow();
    try {
      storeProcessedImage(processedImage, download.pendingSource().replacement(), attemptedAt);
      return new Saved(imageType);
    } catch (RuntimeException e) {
      imageService.deleteFiles(processedImage.writtenFiles());
      log.error(
          "Failed to save images for entity {} ({})", artwork.entityId(), artwork.entityType(), e);
      return new Failed(imageType, e);
    }
  }

  private void storeProcessedImage(
      ProcessedImage processedImage, boolean replacement, Instant attemptedAt) {
    if (replacement) {
      imageService.replaceImages(processedImage, attemptedAt);
      return;
    }

    imageService.saveImages(processedImage.images(), attemptedAt);
  }

  private void recordUnsavedResults(
      ArtworkSources artwork, List<ArtworkResult> results, Instant attemptedAt) {
    for (var result : results) {
      unsavedOutcome(result)
          .ifPresent(
              outcome ->
                  itemResults.tryRecord(
                      ItemResult.builder()
                          .itemId(artwork.entityId())
                          .itemType(artwork.entityType())
                          .step(ItemStep.ARTWORK)
                          .imageType(result.imageType())
                          .outcome(outcome)
                          .sourceKey(sourceKeyOf(artwork, result.imageType()))
                          .attemptedAt(attemptedAt)
                          .build()));
    }
  }

  // ImageService records a saved image's result in the transaction that stores the image.
  private static Optional<ItemOutcome> unsavedOutcome(ArtworkResult result) {
    return switch (result) {
      case Saved _, Skipped _ -> Optional.empty();
      case Unavailable _ -> Optional.of(new ItemOutcome.Unavailable());
      case Failed(var _, var cause) -> Optional.of(ItemOutcome.Failed.of(reasonFor(cause), cause));
    };
  }

  private static ItemFailureReason reasonFor(Throwable cause) {
    return switch (cause) {
      case IOException _ -> ItemFailureReason.DOWNLOAD_FAILED;
      case ImageProcessingException _ -> ItemFailureReason.INVALID_MEDIA;
      default -> ItemFailureReason.TEMPORARY;
    };
  }

  private static String sourceKeyOf(ArtworkSources artwork, ImageType imageType) {
    return artwork.sources().stream()
        .filter(source -> source.imageType() == imageType)
        .map(ImageSource::key)
        .findFirst()
        .orElse(null);
  }

  private static ArtworkResult failedDownload(
      ArtworkSources artwork, ImageType imageType, Throwable cause) {
    if (cause instanceof InterruptedException) {
      log.warn(
          "Image processing interrupted for entity {} ({})",
          artwork.entityId(),
          artwork.entityType());
      return new Failed(imageType, cause);
    }

    log.error(
        "Failed to process image {} for entity {} ({})",
        imageType,
        artwork.entityId(),
        artwork.entityType(),
        cause);
    return new Failed(imageType, cause);
  }
}
