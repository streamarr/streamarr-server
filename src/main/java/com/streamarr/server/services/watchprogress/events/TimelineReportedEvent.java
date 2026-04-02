package com.streamarr.server.services.watchprogress.events;

import com.streamarr.server.domain.streaming.PlaybackState;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TimelineReportedEvent(
    UUID userId,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    PlaybackState state) {}
