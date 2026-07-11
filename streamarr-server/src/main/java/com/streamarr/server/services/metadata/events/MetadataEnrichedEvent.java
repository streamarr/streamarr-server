package com.streamarr.server.services.metadata.events;

import com.streamarr.server.domain.media.ImageEntityType;
import java.util.List;
import java.util.UUID;

public record MetadataEnrichedEvent(
    UUID entityId, ImageEntityType entityType, List<ImageSource> imageSources) {}
