package com.streamarr.server.services;

import com.streamarr.server.domain.media.ItemOutcome;
import lombok.Builder;

/** Finished source images by result; resized variants of one source image count once. */
@Builder(toBuilder = true)
public record ArtworkCounts(int saved, int skipped, int unavailable, int failed) {

  static final ArtworkCounts NONE = ArtworkCounts.builder().build();

  /** Counts a recorded artwork result. A stored image that a step kept counts as saved. */
  ArtworkCounts plus(ItemOutcome outcome) {
    return switch (outcome) {
      case ItemOutcome.Succeeded _ -> toBuilder().saved(saved + 1).build();
      case ItemOutcome.Unavailable _ -> toBuilder().unavailable(unavailable + 1).build();
      case ItemOutcome.Failed _ -> toBuilder().failed(failed + 1).build();
    };
  }

  /** Counts stored images that a run kept without requesting them again. */
  ArtworkCounts plusSkipped(int kept) {
    return toBuilder().skipped(skipped + kept).build();
  }

  public String describe() {
    return "%d saved, %d skipped, %d unavailable, %d failed"
        .formatted(saved, skipped, unavailable, failed);
  }
}
