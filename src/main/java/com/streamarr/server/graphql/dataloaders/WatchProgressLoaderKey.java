package com.streamarr.server.graphql.dataloaders;

import java.util.UUID;

public record WatchProgressLoaderKey(UUID entityId, WatchStatusEntityType entityType) {}
