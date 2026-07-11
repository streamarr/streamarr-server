package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackState;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CommittedStreamSessionTimeline(
    UUID streamSessionId, int positionSeconds, PlaybackState state, Instant accessedAt) {}
