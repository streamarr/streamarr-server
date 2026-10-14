package com.streamarr.server.domain.media;

import java.util.UUID;
import lombok.NonNull;

/**
 * The catalog item whose artwork is requested: a movie, series, season, episode, person or company.
 */
public record ArtworkItem(@NonNull UUID itemId, @NonNull ImageEntityType itemType) {}
