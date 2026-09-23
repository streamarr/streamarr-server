package com.streamarr.server.services;

import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ImageEnrichmentListener {

  private final ArtworkFetcher artworkFetcher;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMetadataEnriched(MetadataEnrichedEvent event) {
    var artwork =
        ArtworkSources.builder()
            .entityId(event.entityId())
            .entityType(event.entityType())
            .sources(event.imageSources())
            .build();
    Thread.startVirtualThread(() -> artworkFetcher.fetch(artwork, event.imageRefreshMode()));
  }
}
