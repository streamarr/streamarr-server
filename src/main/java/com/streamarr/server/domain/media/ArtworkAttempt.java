package com.streamarr.server.domain.media;

import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;

/** One attempt at an item's artwork steps, for one generation of the item's artwork request. */
@Builder
public record ArtworkAttempt(
    @NonNull ArtworkItem item, long generation, @NonNull Instant attemptedAt) {}
