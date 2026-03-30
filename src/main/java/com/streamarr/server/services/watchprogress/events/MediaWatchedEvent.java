package com.streamarr.server.services.watchprogress.events;

import java.util.UUID;

public record MediaWatchedEvent(UUID userId, UUID mediaFileId) {}
