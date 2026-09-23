package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  private final MutexFactory<String> mutexFactory;

  public ArtworkFetcher(
      TmdbImageDownloader tmdbImageDownloader,
      ImageService imageService,
      MutexFactoryProvider mutexFactoryProvider) {
    this.tmdbImageDownloader = tmdbImageDownloader;
    this.imageService = imageService;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  /** Returns one result per requested source image; failures are results, never exceptions. */
  public List<ArtworkResult> fetch(ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var mutex = mutexFactory.getMutex(artwork.entityId().toString());

    try {
      mutex.lockInterruptibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Image enrichment interrupted for entity {}", artwork.entityId());
      return artwork.failures(e);
    }

    try {
      return fetchWhileLocked(artwork, refreshMode);
    } catch (RuntimeException e) {
      log.error(
          "Failed to fetch images for entity {} ({})", artwork.entityId(), artwork.entityType(), e);
      return artwork.failures(e);
    } finally {
      mutex.unlock();
    }
  }

  private List<ArtworkResult> fetchWhileLocked(
      ArtworkSources artwork, ImageRefreshMode refreshMode) {
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

    results.addAll(downloadAllImages(artwork, pendingSources));
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
      ArtworkSources artwork, List<PendingImageSource> pendingSources) {
    var downloads = new ArrayList<ImageDownload>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var pendingSource : pendingSources) {
        downloads.add(
            new ImageDownload(
                pendingSource,
                executor.submit(() -> downloadAndProcessImage(pendingSource, artwork))));
      }
    }

    return downloads.stream().map(download -> saveDownloadedImage(artwork, download)).toList();
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

  private ArtworkResult saveDownloadedImage(ArtworkSources artwork, ImageDownload download) {
    var imageType = download.pendingSource().source().imageType();
    if (download.processedImage().state() == Future.State.FAILED) {
      return failedDownload(artwork, imageType, download.processedImage().exceptionNow());
    }

    var processedImage = download.processedImage().resultNow();
    try {
      storeProcessedImage(processedImage, download.pendingSource().replacement());
      return new Saved(imageType);
    } catch (RuntimeException e) {
      imageService.deleteFiles(processedImage.writtenFiles());
      log.error(
          "Failed to save images for entity {} ({})", artwork.entityId(), artwork.entityType(), e);
      return new Failed(imageType, e);
    }
  }

  private void storeProcessedImage(ProcessedImage processedImage, boolean replacement) {
    if (replacement) {
      imageService.replaceImages(processedImage);
      return;
    }

    imageService.saveImages(processedImage.images());
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
