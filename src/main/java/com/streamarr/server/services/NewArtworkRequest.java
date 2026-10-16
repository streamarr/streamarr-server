package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * An artwork request for one target, before it is merged with the target's pending request.
 *
 * @param libraryOperationId the library operation that waits for the artwork; a background request
 *     and a request from outside an operation have none
 */
@Builder
public record NewArtworkRequest(
    @NonNull ArtworkSources artwork,
    @NonNull ImageRefreshMode refreshMode,
    @NonNull Instant requestedAt,
    Optional<UUID> libraryOperationId) {

  public NewArtworkRequest {
    libraryOperationId = Objects.requireNonNullElse(libraryOperationId, Optional.empty());
  }
}
