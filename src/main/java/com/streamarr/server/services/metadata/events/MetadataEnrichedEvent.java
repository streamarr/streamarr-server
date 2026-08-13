package com.streamarr.server.services.metadata.events;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.List;
import java.util.UUID;

public record MetadataEnrichedEvent(
    UUID entityId,
    ImageEntityType entityType,
    List<ImageSource> imageSources,
    ImageRefreshMode imageRefreshMode) {

  public MetadataEnrichedEvent(
      UUID entityId, ImageEntityType entityType, List<ImageSource> imageSources) {
    this(entityId, entityType, imageSources, ImageRefreshMode.PRESERVE);
  }
}
