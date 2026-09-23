package com.streamarr.server.services;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

/**
 * The required artwork of one finished run. {@code elapsed} runs from the first required request
 * until the last request finished and the run closed to new requests.
 */
@Builder
public record ArtworkRunSummary(
    @NonNull String description, @NonNull ArtworkCounts counts, @NonNull Duration elapsed) {}
