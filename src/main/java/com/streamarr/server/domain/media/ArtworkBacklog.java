package com.streamarr.server.domain.media;

import java.time.Instant;
import lombok.NonNull;

/**
 * The images that pending artwork requests still need, and when the oldest of those requests was
 * first made. An image needs nothing more once it has a final result of its request's generation.
 */
public record ArtworkBacklog(long pendingImages, @NonNull Instant oldestRequestAt) {

  /** Adds the pending images of {@code other} and keeps the older request time. */
  public ArtworkBacklog plus(@NonNull ArtworkBacklog other) {
    var oldest = oldestRequestAt;
    if (other.oldestRequestAt.isBefore(oldest)) {
      oldest = other.oldestRequestAt;
    }

    return new ArtworkBacklog(pendingImages + other.pendingImages, oldest);
  }
}
