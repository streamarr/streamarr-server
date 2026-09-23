package com.streamarr.server.domain.media;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The outcome of the latest attempt at one step for one catalog item. Artwork results name the
 * image type and, when the provider offered one, the source key the attempt used.
 */
@Builder(toBuilder = true)
public record ItemResult(
    @NonNull UUID itemId,
    @NonNull ImageEntityType itemType,
    @NonNull ItemStep step,
    ImageType imageType,
    @NonNull ItemOutcome outcome,
    String sourceKey,
    @NonNull Instant attemptedAt) {

  public ItemResult {
    if ((step == ItemStep.ARTWORK) != (imageType != null)) {
      throw new IllegalArgumentException("Only artwork results name an image type");
    }
  }
}
