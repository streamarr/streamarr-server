package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/** The tier of an AV1 or H.265 video stream. H.264 has no tier. An encode forces only MAIN. */
public enum VideoCodecTier {
  MAIN,
  HIGH;

  /** Whether this tier is at or below the maximum. The High tier is above the Main tier. */
  public boolean isAtMost(@NonNull VideoCodecTier maximum) {
    return compareTo(maximum) <= 0;
  }
}
