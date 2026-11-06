package com.streamarr.server.domain.streaming;

/**
 * A change that the worker makes to a copied video stream without decoding it (ADR 0036). A stream
 * copy passes the source's video through unchanged apart from the rewrites that its planned output
 * lists.
 */
public enum BitstreamRewrite {
  /** Removes every Dolby Vision RPU, every enhancement-layer unit and the configuration record. */
  STRIP_DOLBY_VISION,
  /** Removes the HDR10+ messages. */
  STRIP_HDR10_PLUS,
  /** Writes the Dolby Vision configuration record that the copy's output needs. */
  NORMALIZE_DOLBY_VISION_RECORD,
  /**
   * Converts single-track Dolby Vision profile 7 to profile 8.1 (ADR 0036,
   * streamarr/streamarr-server#651): removes every enhancement-layer unit and rewrites each
   * picture's RPU without its residual, as libdovi's mode 2 does. The copy then carries profile 8.1
   * on its PQ base and lists {@link #NORMALIZE_DOLBY_VISION_RECORD} for the profile 8.1 record.
   */
  CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1
}
