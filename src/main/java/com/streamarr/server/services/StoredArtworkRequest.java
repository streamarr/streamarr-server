package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import lombok.Builder;
import lombok.NonNull;

/**
 * A target's stored artwork request: the sources and the refresh mode its artwork steps follow, and
 * the generation those steps record their results for.
 */
@Builder
public record StoredArtworkRequest(
    @NonNull ArtworkSources artwork, @NonNull ImageRefreshMode refreshMode, long generation) {}
