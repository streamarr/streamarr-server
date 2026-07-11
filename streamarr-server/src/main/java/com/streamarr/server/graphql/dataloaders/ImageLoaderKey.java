package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.domain.media.ImageEntityType;
import java.util.UUID;

public record ImageLoaderKey(UUID entityId, ImageEntityType entityType) {}
