package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/** A picture's width and height in pixels. */
public record PictureSize(int width, int height) {

  /** This size turned so that its longer side runs the same way as the picture's longer side. */
  public PictureSize orientedLike(@NonNull PictureSize picture) {
    var longer = Math.max(width, height);
    var shorter = Math.min(width, height);
    if (picture.height() > picture.width()) {
      return new PictureSize(shorter, longer);
    }

    return new PictureSize(longer, shorter);
  }

  /** This size with each side cut to the bounds' side in the same direction. */
  public PictureSize boundedBy(@NonNull PictureSize bounds) {
    return new PictureSize(Math.min(width, bounds.width()), Math.min(height, bounds.height()));
  }

  /** Whether this picture fits within the bounds in either orientation. */
  public boolean fitsWithinEitherOrientation(@NonNull PictureSize bounds) {
    var oriented = bounds.orientedLike(this);
    return width <= oriented.width() && height <= oriented.height();
  }
}
