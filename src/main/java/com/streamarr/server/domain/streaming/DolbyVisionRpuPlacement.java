package com.streamarr.server.domain.streaming;

/**
 * Where the samples of an H.264 or HEVC Dolby Vision stream place each picture's RPU, as probe
 * version 5 reads it from the first packets (ADR 0036). Each picture's RPU belongs at the end of
 * its own picture's sample.
 */
public enum DolbyVisionRpuPlacement {
  /**
   * The probe could not decide, the stream is AV1, or the packets matched no other placement. A
   * first picture packet that starts with an RPU gives this value too, because the first packets
   * cannot tell a BEFORE_NEXT_PICTURE file whose start was cut away from a file that starts each
   * sample with its own picture's RPU.
   */
  UNSPECIFIED,
  /** Each RPU ends its own picture's sample. */
  AFTER_OWN_PICTURE,
  /** Each RPU starts the next picture's sample, as mkvmerge wrote before version 57. */
  BEFORE_NEXT_PICTURE;

  /** Whether the probe found each RPU at the start of the next picture's sample. */
  public boolean startsSample() {
    return switch (this) {
      case BEFORE_NEXT_PICTURE -> true;
      case UNSPECIFIED, AFTER_OWN_PICTURE -> false;
    };
  }
}
