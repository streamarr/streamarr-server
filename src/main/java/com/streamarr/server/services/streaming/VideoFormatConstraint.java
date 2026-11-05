package com.streamarr.server.services.streaming;

/** Permitted video operations for a format attempt; audio negotiation remains independent. */
public enum VideoFormatConstraint {
  /**
   * The format attempt may send the source file itself (ADR 0045), copy the video, or encode it.
   */
  DIRECT_PLAY_OR_COPY_OR_ENCODE,
  COPY_OR_ENCODE,
  /**
   * The format attempt may copy the video only with its Dolby Vision stripped, or encode it. It
   * follows a failed Dolby Vision copy (ADR 0052).
   */
  COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE,
  ENCODE_ONLY
}
