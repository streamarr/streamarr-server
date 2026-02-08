package com.streamarr.server.services.metadata;

import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageEnrichmentListener {

  private final TheMovieDatabaseHttpService tmdbHttpService;
  private final ImageService imageService;
  private final ImageRepository imageRepository;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMetadataEnriched(MetadataEnrichedEvent event) {
    var existingImages =
        imageRepository.findByEntityIdAndEntityType(event.entityId(), event.entityType());

    if (!existingImages.isEmpty()) {
      log.debug(
          "Images already exist for entity {} ({}), skipping",
          event.entityId(),
          event.entityType());
      return;
    }

    for (var source : event.imageSources()) {
      try {
        var imageData =
            switch (source) {
              case TmdbImageSource tmdb -> tmdbHttpService.downloadImage(tmdb.pathFragment());
            };

        imageService.processAndSaveImage(
            imageData, source.imageType(), event.entityId(), event.entityType());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error(
            "Image processing interrupted for {} entity {}",
            source.imageType(),
            event.entityId(),
            e);
      } catch (Exception e) {
        log.error(
            "Failed to process image {} for entity {} ({})",
            source.imageType(),
            event.entityId(),
            event.entityType(),
            e);
      }
    }
  }
}
