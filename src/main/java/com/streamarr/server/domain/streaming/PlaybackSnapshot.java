package com.streamarr.server.domain.streaming;

import java.time.Instant;

public record PlaybackSnapshot(
    int positionSeconds, PlaybackState state, Instant accessedAt, int seekOrigin) {}
