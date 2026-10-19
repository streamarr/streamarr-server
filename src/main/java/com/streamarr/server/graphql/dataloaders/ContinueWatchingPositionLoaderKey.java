package com.streamarr.server.graphql.dataloaders;

import java.util.UUID;

public record ContinueWatchingPositionLoaderKey(UUID profileId, UUID itemId) {}
