package com.streamarr.server.services;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

/**
 * The primary artwork of one finished run. {@code elapsed} runs from the run's first request until
 * the wait found every awaited result recorded.
 */
@Builder
public record ArtworkRunSummary(
    @NonNull String description, @NonNull ArtworkCounts counts, @NonNull Duration elapsed) {}
