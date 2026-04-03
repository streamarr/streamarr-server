package com.streamarr.server.repositories.streaming;

import java.util.UUID;
import lombok.Builder;

@Builder
public record SaveProgressCommand(
    UUID sessionId,
    UUID userId,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    int durationSeconds) {}
