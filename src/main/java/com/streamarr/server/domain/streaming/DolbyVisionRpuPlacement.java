package com.streamarr.server.domain.streaming;

/**
 * Where the samples of an H.264 or HEVC Dolby Vision stream place each picture's RPU, as probe
 * version 5 reads it from the first packets (ADR 0036). Each picture's RPU belongs at the end of
 * its own picture's sample.
 */
public enum DolbyVisionRpuPlacement {
  /** The probe could not tell the placement from the first packets, or the stream is AV1. */
  UNSPECIFIED,
  /** Each RPU ends its own picture's sample. */
  AFTER_OWN_PICTURE,
  /**
   * Every packet that holds an RPU starts with it, whether or not the first picture packet holds
   * one. mkvmerge before version 57 wrote each picture's RPU at the start of the next picture's
   * sample, and a file whose start was cut away keeps that layout from its first picture packet.
   */
  STARTS_SAMPLE;

  /** Whether the probe found every RPU at the start of its sample. */
  public boolean startsSample() {
    return switch (this) {
      case STARTS_SAMPLE -> true;
      case UNSPECIFIED, AFTER_OWN_PICTURE -> false;
    };
  }
}
