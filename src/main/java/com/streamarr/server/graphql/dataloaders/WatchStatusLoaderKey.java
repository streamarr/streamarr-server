package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.domain.streaming.CollectableScope;
import java.util.UUID;

public record WatchStatusLoaderKey(UUID entityId, CollectableScope scope) {}
