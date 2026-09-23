package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ArtworkSources(
    @NonNull UUID entityId,
    @NonNull ImageEntityType entityType,
    @NonNull List<ImageSource> sources) {}
