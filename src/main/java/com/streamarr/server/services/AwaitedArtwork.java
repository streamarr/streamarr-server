package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageType;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The artwork that a run waits for from one target: a result of {@code generation} or a newer one
 * for each awaited image type. A run of a library operation waits only for results that carry the
 * operation's id.
 */
@Builder
public record AwaitedArtwork(
    @NonNull ArtworkTarget target,
    long generation,
    @NonNull Set<ImageType> awaitedImageTypes,
    Optional<UUID> libraryOperationId) {

  public AwaitedArtwork {
    awaitedImageTypes = Set.copyOf(awaitedImageTypes);
    libraryOperationId = Objects.requireNonNullElse(libraryOperationId, Optional.empty());
  }
}
