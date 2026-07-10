package com.streamarr.server.graphql.dataloaders;

import java.util.UUID;

public record SessionProgressLoaderKey(UUID profileId, UUID mediaFileId) {}
