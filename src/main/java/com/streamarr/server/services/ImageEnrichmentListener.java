package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.services.ImageService.ProcessedImage;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ImageEnrichmentListener {

  private record PendingImageSource(ImageSource source, boolean replacement) {}

  private record ProcessedImageResult(ProcessedImage processedImage, boolean replacement) {}

  private final TmdbImageDownloader tmdbImageDownloader;
  private final ImageService imageService;
  private final MutexFactory<String> mutexFactory;

  public ImageEnrichmentListener(
      TmdbImageDownloader tmdbImageDownloader,
      ImageService imageService,
      MutexFactoryProvider mutexFactoryProvider) {
    this.tmdbImageDownloader = tmdbImageDownloader;
    this.imageService = imageService;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMetadataEnriched(MetadataEnrichedEvent event) {
    Thread.startVirtualThread(() -> enrichImages(event));
  }

  private void enrichImages(MetadataEnrichedEvent event) {
    var mutex = mutexFactory.getMutex(event.entityId().toString());

    try {
      mutex.lockInterruptibly();

      try {
        var existingImages = imageService.findByEntity(event.entityId(), event.entityType());
        var existingImagesByType =
            existingImages.stream().collect(Collectors.groupingBy(Image::getImageType));
        var pendingSources = new ArrayList<PendingImageSource>();
        for (var source : event.imageSources()) {
          var existingForType = existingImagesByType.getOrDefault(source.imageType(), List.of());
          if (requiresDownload(source, existingForType, event.imageRefreshMode())) {
            pendingSources.add(new PendingImageSource(source, !existingForType.isEmpty()));
          }
        }

        if (pendingSources.isEmpty()) {
          log.debug(
              "All image types already exist for entity {} ({}), skipping",
              event.entityId(),
              event.entityType());
          return;
        }

        downloadAllImages(event, pendingSources);
      } finally {
        mutex.unlock();
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      log.warn("Image enrichment interrupted for entity {}", event.entityId());
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

  private void downloadAllImages(
      MetadataEnrichedEvent event, List<PendingImageSource> imageSources) {
    var futures = new ArrayList<Future<ProcessedImageResult>>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var pendingSource : imageSources) {
        futures.add(executor.submit(() -> downloadAndProcessImage(pendingSource, event)));
      }
    }

    var processedResults =
        futures.stream().map(Future::resultNow).filter(Objects::nonNull).toList();

    for (var result : processedResults) {
      saveProcessedImage(event, result);
    }
  }

  private void saveProcessedImage(MetadataEnrichedEvent event, ProcessedImageResult result) {
    try {
      if (result.replacement()) {
        imageService.replaceImages(result.processedImage());
        return;
      }

      imageService.saveImages(result.processedImage().images());
    } catch (Exception e) {
      imageService.deleteFiles(result.processedImage().writtenFiles());
      log.error(
          "Failed to save images for entity {} ({})", event.entityId(), event.entityType(), e);
    }
  }

  private ProcessedImageResult downloadAndProcessImage(
      PendingImageSource pendingSource, MetadataEnrichedEvent event) {
    var source = pendingSource.source();

    try {
      var imageData =
          switch (source) {
            case TmdbImageSource tmdb -> tmdbImageDownloader.downloadImage(tmdb.key());
          };

      var processedImage =
          imageService.processImage(
              imageData, source.imageType(), event.entityId(), event.entityType(), source.key());
      return new ProcessedImageResult(processedImage, pendingSource.replacement());
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      log.warn(
          "Image processing interrupted for entity {} ({})", event.entityId(), event.entityType());
      return null;
    } catch (Exception e) {
      log.error(
          "Failed to process image {} for entity {} ({})",
          source.imageType(),
          event.entityId(),
          event.entityType(),
          e);
      return null;
    }
  }
}
