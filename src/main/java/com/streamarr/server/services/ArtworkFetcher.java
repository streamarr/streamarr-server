package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.services.ImageService.ProcessedImage;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
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

  private record ProcessedImageResult(ProcessedImage processedImage, boolean replacement) {}

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

  public void fetch(ArtworkSources artwork, ImageRefreshMode refreshMode) {
    var mutex = mutexFactory.getMutex(artwork.entityId().toString());

    try {
      mutex.lockInterruptibly();

      try {
        var existingImages = imageService.findByEntity(artwork.entityId(), artwork.entityType());
        var existingImagesByType =
            existingImages.stream().collect(Collectors.groupingBy(Image::getImageType));
        var pendingSources = new ArrayList<PendingImageSource>();
        for (var source : artwork.sources()) {
          var existingForType = existingImagesByType.getOrDefault(source.imageType(), List.of());
          if (requiresDownload(source, existingForType, refreshMode)) {
            pendingSources.add(new PendingImageSource(source, !existingForType.isEmpty()));
          }
        }

        if (pendingSources.isEmpty()) {
          log.debug(
              "All image types already exist for entity {} ({}), skipping",
              artwork.entityId(),
              artwork.entityType());
          return;
        }

        downloadAllImages(artwork, pendingSources);
      } finally {
        mutex.unlock();
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      log.warn("Image enrichment interrupted for entity {}", artwork.entityId());
    }
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

  private void downloadAllImages(ArtworkSources artwork, List<PendingImageSource> imageSources) {
    var futures = new ArrayList<Future<ProcessedImageResult>>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var pendingSource : imageSources) {
        futures.add(executor.submit(() -> downloadAndProcessImage(pendingSource, artwork)));
      }
    }

    var processedResults =
        futures.stream().map(Future::resultNow).filter(Objects::nonNull).toList();

    for (var result : processedResults) {
      saveProcessedImage(artwork, result);
    }
  }

  private void saveProcessedImage(ArtworkSources artwork, ProcessedImageResult result) {
    try {
      if (result.replacement()) {
        imageService.replaceImages(result.processedImage());
        return;
      }

      imageService.saveImages(result.processedImage().images());
    } catch (Exception e) {
      imageService.deleteFiles(result.processedImage().writtenFiles());
      log.error(
          "Failed to save images for entity {} ({})", artwork.entityId(), artwork.entityType(), e);
    }
  }

  private ProcessedImageResult downloadAndProcessImage(
      PendingImageSource pendingSource, ArtworkSources artwork) {
    var source = pendingSource.source();

    try {
      var imageData =
          switch (source) {
            case TmdbImageSource tmdb -> tmdbImageDownloader.downloadImage(tmdb.key());
          };

      var processedImage =
          imageService.processImage(
              imageData,
              source.imageType(),
              artwork.entityId(),
              artwork.entityType(),
              source.key());
      return new ProcessedImageResult(processedImage, pendingSource.replacement());
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      log.warn(
          "Image processing interrupted for entity {} ({})",
          artwork.entityId(),
          artwork.entityType());
      return null;
    } catch (Exception e) {
      log.error(
          "Failed to process image {} for entity {} ({})",
          source.imageType(),
          artwork.entityId(),
          artwork.entityType(),
          e);
      return null;
    }
  }
}
