package com.streamarr.server.domain.media;

import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;

/** One attempt at a target's artwork steps, for one generation of the target's artwork request. */
@Builder
public record ArtworkStepsAttempt(
    @NonNull ArtworkTarget target, long generation, @NonNull Instant attemptedAt) {}
