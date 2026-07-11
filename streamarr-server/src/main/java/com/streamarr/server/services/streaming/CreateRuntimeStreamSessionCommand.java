package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamingOptions;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreateRuntimeStreamSessionCommand(
    UUID streamSessionId,
    UUID mediaFileId,
    UUID profileId,
    StreamingOptions options,
    Instant initialLastAccessedAt,
    RuntimeSessionReservation reservation) {}
