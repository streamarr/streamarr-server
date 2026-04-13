package com.streamarr.server.services.watchprogress.events;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ItemWatchedEvent(UUID sessionId, UUID userId, UUID mediaFileId, UUID collectableId) {}
