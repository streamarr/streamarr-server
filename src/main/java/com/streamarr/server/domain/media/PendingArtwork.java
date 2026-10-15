package com.streamarr.server.domain.media;

import java.time.Instant;
import lombok.NonNull;

/**
 * The images that pending artwork requests still need, and when the earliest of those requests was
 * first made. An image needs nothing more once it has a final result of its request's generation.
 */
public record PendingArtwork(long images, @NonNull Instant firstRequestedAt) {

  /** Adds the images of {@code other} and keeps the earlier first request time. */
  public PendingArtwork plus(@NonNull PendingArtwork other) {
    var earliest = firstRequestedAt;
    if (other.firstRequestedAt.isBefore(earliest)) {
      earliest = other.firstRequestedAt;
    }

    return new PendingArtwork(images + other.images, earliest);
  }
}
