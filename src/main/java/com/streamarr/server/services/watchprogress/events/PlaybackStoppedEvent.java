package com.streamarr.server.services.watchprogress.events;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PlaybackStoppedEvent(
    UUID userId,
    UUID sessionId,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    boolean playedToCompletion) {}
