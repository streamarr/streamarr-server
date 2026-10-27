package com.streamarr.server.domain.streaming;

/**
 * Where the samples of an H.264 or HEVC Dolby Vision stream place each picture's RPU, as probe
 * version 5 reads it from the first packets (ADR 0036). Each picture's RPU belongs at the end of
 * its own picture's sample.
 */
public enum DolbyVisionRpuPlacement {
  /** The probe could not decide, the stream is AV1, or the packets matched no other placement. */
  UNSPECIFIED,
  /** Each RPU ends its own picture's sample. */
  AFTER_OWN_PICTURE,
  /** Each RPU starts the next picture's sample, as mkvmerge wrote before version 57. */
  BEFORE_NEXT_PICTURE,
  /** Each RPU starts its own picture's sample. */
  BEFORE_OWN_PICTURE;

  /** Whether a sample starts with an RPU ahead of its picture. */
  public boolean startsSample() {
    return switch (this) {
      case BEFORE_NEXT_PICTURE, BEFORE_OWN_PICTURE -> true;
      case UNSPECIFIED, AFTER_OWN_PICTURE -> false;
    };
  }
}
