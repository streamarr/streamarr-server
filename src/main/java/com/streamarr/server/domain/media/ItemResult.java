package com.streamarr.server.domain.media;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The outcome of the latest attempt at one step for one catalog item. Artwork results name the
 * image type, the generation of the artwork request they were recorded for, and, when the provider
 * offered one, the source key the attempt used.
 */
@Builder(toBuilder = true)
public record ItemResult(
    @NonNull UUID itemId,
    @NonNull ImageEntityType itemType,
    @NonNull ItemStep step,
    ImageType imageType,
    @NonNull ItemOutcome outcome,
    String sourceKey,
    @NonNull Instant attemptedAt,
    Long artworkGeneration) {

  public ItemResult {
    var artwork = step == ItemStep.ARTWORK;
    if (artwork != (imageType != null)) {
      throw new IllegalArgumentException("Only artwork results name an image type");
    }

    if (artwork != (artworkGeneration != null)) {
      throw new IllegalArgumentException("Only artwork results name an artwork generation");
    }
  }

  /**
   * Returns whether no later attempt of the same artwork generation will replace this result: it
   * succeeded, is unavailable, or failed for a reason that a retry would meet again.
   */
  public boolean isFinal() {
    return switch (outcome) {
      case ItemOutcome.Succeeded _, ItemOutcome.Unavailable _ -> true;
      case ItemOutcome.Failed(var reason, var _) -> reason != ItemFailureReason.TEMPORARY;
    };
  }
}
