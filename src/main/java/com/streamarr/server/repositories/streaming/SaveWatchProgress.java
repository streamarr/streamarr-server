package com.streamarr.server.repositories.streaming;

import java.util.UUID;
import lombok.Builder;

@Builder
public record SaveWatchProgress(
    UUID sessionId,
    UUID userId,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    int durationSeconds) {}
