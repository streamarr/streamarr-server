package com.streamarr.server.services;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

/**
 * Server-wide progress of one artwork priority since its busy period started. {@code pending}
 * counts the images that stored requests ask for, and a report with nothing pending closes the busy
 * period.
 */
@Builder
public record ArtworkProgressReport(
    @NonNull ArtworkPriority priority,
    @NonNull FinishedImageCounts counts,
    long pending,
    @NonNull Duration elapsed) {

  public boolean isFinished() {
    return pending == 0;
  }
}
