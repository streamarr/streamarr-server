package com.streamarr.server.domain.streaming;

import java.time.Instant;
import lombok.Builder;

@Builder
public record PlaybackSnapshot(
    int positionSeconds, PlaybackState state, Instant accessedAt, int seekOrigin) {}
