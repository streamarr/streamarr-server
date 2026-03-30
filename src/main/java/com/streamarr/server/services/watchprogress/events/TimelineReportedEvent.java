package com.streamarr.server.services.watchprogress.events;

import com.streamarr.server.domain.streaming.PlaybackState;
import java.util.UUID;

public record TimelineReportedEvent(
    UUID userId,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    PlaybackState state) {}
