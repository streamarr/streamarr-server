package com.streamarr.server.services.streaming;

/** Permitted video operations for a format attempt; audio negotiation remains independent. */
public enum VideoFormatConstraint {
  /**
   * The format attempt may send the source file itself (ADR 0045), copy the video, or encode it.
   */
  DIRECT_PLAY_OR_COPY_OR_ENCODE,
  COPY_OR_ENCODE,
  ENCODE_ONLY
}
