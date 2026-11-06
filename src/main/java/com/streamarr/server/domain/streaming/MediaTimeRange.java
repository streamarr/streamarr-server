package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/** A stretch of media time, from {@code start} up to {@code end}. */
public record MediaTimeRange(@NonNull Duration start, @NonNull Duration end) {}
