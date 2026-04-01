package com.streamarr.server.graphql.dataloaders;

import java.util.UUID;

public record WatchStatusLoaderKey(UUID entityId, WatchStatusEntityType entityType) {}
