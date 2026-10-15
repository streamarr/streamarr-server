package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.domain.streaming.CollectableScope;
import java.util.UUID;

public record LastWatchedAtLoaderKey(UUID profileId, UUID entityId, CollectableScope scope) {}
