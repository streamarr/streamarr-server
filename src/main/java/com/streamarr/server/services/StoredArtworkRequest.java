package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A target's stored artwork request: the sources and the refresh mode its artwork steps follow, the
 * generation those steps save their results for, and the library operation that waits for it, if
 * any.
 */
@Builder
public record StoredArtworkRequest(
    @NonNull ArtworkSources artwork,
    @NonNull ImageRefreshMode refreshMode,
    long generation,
    Optional<UUID> libraryOperationId) {

  public StoredArtworkRequest {
    libraryOperationId = Objects.requireNonNullElse(libraryOperationId, Optional.empty());
  }
}
