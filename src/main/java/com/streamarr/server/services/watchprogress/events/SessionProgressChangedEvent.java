package com.streamarr.server.services.watchprogress.events;

import com.streamarr.server.domain.streaming.PlaybackState;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SessionProgressChangedEvent(
    UUID sessionId,
    UUID userId,
    UUID mediaFileId,
    UUID collectableId,
    int positionSeconds,
    double percentComplete,
    PlaybackState state) {}
