package com.streamarr.server.services.metadata;

import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.ImageService.ProcessedImage;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ImageEnrichmentListener {

  private final TheMovieDatabaseHttpService tmdbHttpService;
  private final ImageService imageService;
  private final MutexFactory<String> mutexFactory;

  public ImageEnrichmentListener(
      TheMovieDatabaseHttpService tmdbHttpService,
      ImageService imageService,
      MutexFactoryProvider mutexFactoryProvider) {
    this.tmdbHttpService = tmdbHttpService;
    this.imageService = imageService;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMetadataEnriched(MetadataEnrichedEvent event) {
    var mutex = mutexFactory.getMutex(event.entityId().toString());

    mutex.lock();
    try {
      var existingImages = imageService.findByEntity(event.entityId(), event.entityType());

      if (!existingImages.isEmpty()) {
        log.debug(
            "Images already exist for entity {} ({}), skipping",
            event.entityId(),
            event.entityType());
        return;
      }

      downloadAllImages(event);
    } finally {
      mutex.unlock();
    }
  }

  private void downloadAllImages(MetadataEnrichedEvent event) {
    var futures = new ArrayList<Future<ProcessedImage>>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var source : event.imageSources()) {
        futures.add(executor.submit(() -> downloadAndProcessImage(source, event)));
      }
    }

    var processedResults =
        futures.stream().map(ImageEnrichmentListener::getQuietly).filter(Objects::nonNull).toList();

    if (processedResults.isEmpty()) {
      return;
    }

    var allWrittenFiles =
        processedResults.stream().flatMap(r -> r.writtenFiles().stream()).toList();

    try {
      imageService.saveImages(processedResults.stream().flatMap(r -> r.images().stream()).toList());
    } catch (Exception e) {
      imageService.deleteFiles(allWrittenFiles);
      log.error(
          "Failed to save images for entity {} ({})", event.entityId(), event.entityType(), e);
    }
  }

  private ProcessedImage downloadAndProcessImage(ImageSource source, MetadataEnrichedEvent event) {
    try {
      var imageData =
          switch (source) {
            case TmdbImageSource tmdb -> tmdbHttpService.downloadImage(tmdb.pathFragment());
          };

      return imageService.processImage(
          imageData, source.imageType(), event.entityId(), event.entityType());
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

  private static ProcessedImage getQuietly(Future<ProcessedImage> future) {
    try {
      return future.get();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception _) {
      return null;
    }
  }
}
