package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkResultCounts;
import lombok.Builder;

/** Finished source images by result; resized variants of one source image count once. */
@Builder(toBuilder = true)
public record FinishedImageCounts(int saved, int skipped, int unavailable, int failed) {

  static final FinishedImageCounts NONE = FinishedImageCounts.builder().build();

  /** Counts recorded artwork results. A stored image that a step kept counts as saved. */
  FinishedImageCounts plus(ArtworkResultCounts resultCounts) {
    return toBuilder()
        .saved(saved + Math.toIntExact(resultCounts.saved()))
        .unavailable(unavailable + Math.toIntExact(resultCounts.unavailable()))
        .failed(failed + Math.toIntExact(resultCounts.failed()))
        .build();
  }

  /** Counts stored images that a run kept without requesting them again. */
  FinishedImageCounts plusSkipped(int kept) {
    return toBuilder().skipped(skipped + kept).build();
  }

  public String describe() {
    return "%d saved, %d skipped, %d unavailable, %d failed"
        .formatted(saved, skipped, unavailable, failed);
  }
}
