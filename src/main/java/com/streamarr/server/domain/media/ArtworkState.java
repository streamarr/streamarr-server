package com.streamarr.server.domain.media;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * The recorded artwork of one item: the generation of its pending artwork request, if one is
 * pending, and its artwork results.
 */
public record ArtworkState(
    @NonNull ArtworkItem item,
    @NonNull Optional<Long> pendingGeneration,
    @NonNull List<ItemResult> artworkResults) {

  public ArtworkState {
    artworkResults = List.copyOf(artworkResults);
  }
}
