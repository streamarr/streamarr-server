package com.streamarr.server.services;

import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ImageEnrichmentListener {

  private final ArtworkService artworkService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMetadataEnriched(MetadataEnrichedEvent event) {
    var artwork =
        ArtworkSources.builder()
            .entityId(event.entityId())
            .entityType(event.entityType())
            .sources(event.imageSources())
            .build();
    artworkService.fetchSecondary(artwork, event.imageRefreshMode());
  }
}
