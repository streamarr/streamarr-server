package com.streamarr.server.repositories.streaming;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SaveProgressCommand(
    UUID userId,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    int durationSeconds,
    Instant lastPlayedAt) {}
