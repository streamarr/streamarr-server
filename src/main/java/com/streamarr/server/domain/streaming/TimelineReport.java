package com.streamarr.server.domain.streaming;

import java.time.Instant;

/** A player's report of its position and state, received at {@code reportedAt}. */
public record TimelineReport(int positionSeconds, PlaybackState state, Instant reportedAt) {}
