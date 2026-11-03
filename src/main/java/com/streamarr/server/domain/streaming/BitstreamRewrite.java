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
  NORMALIZE_DOLBY_VISION_RECORD
}
