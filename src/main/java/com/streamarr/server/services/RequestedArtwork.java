package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageType;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * The artwork that a run waits for from one target: a result of {@code generation} or a newer one
 * for each awaited image type.
 */
@Builder
public record RequestedArtwork(
    @NonNull ArtworkTarget target, long generation, @NonNull Set<ImageType> awaitedImageTypes) {

  public RequestedArtwork {
    awaitedImageTypes = Set.copyOf(awaitedImageTypes);
  }
}
