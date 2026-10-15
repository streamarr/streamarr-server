package com.streamarr.server.domain.media;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * The recorded artwork of one target: the generation of its pending artwork request, if one is
 * pending, and its artwork results.
 */
public record ArtworkCompletionEvidence(
    @NonNull ArtworkTarget target,
    @NonNull Optional<Long> pendingGeneration,
    @NonNull List<ItemResult> artworkResults) {

  public ArtworkCompletionEvidence {
    artworkResults = List.copyOf(artworkResults);
  }
}
