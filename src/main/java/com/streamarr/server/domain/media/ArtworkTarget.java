package com.streamarr.server.domain.media;

import java.util.UUID;
import lombok.NonNull;

/** The movie, series, season, episode, person or company whose artwork is requested. */
public record ArtworkTarget(@NonNull UUID targetId, @NonNull ImageEntityType targetType) {}
