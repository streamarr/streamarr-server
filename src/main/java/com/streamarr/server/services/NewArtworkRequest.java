package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;

/** An artwork request for one target, before it is merged with the target's pending request. */
@Builder
public record NewArtworkRequest(
    @NonNull ArtworkSources artwork,
    @NonNull ImageRefreshMode refreshMode,
    @NonNull Instant requestedAt) {}
