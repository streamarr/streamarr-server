package com.streamarr.server.services.watchprogress.events;

import com.streamarr.server.domain.streaming.PlaybackState;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record StreamSessionTimelineCommittedEvent(
    UUID sessionId, int positionSeconds, PlaybackState state, Instant accessedAt) {}
