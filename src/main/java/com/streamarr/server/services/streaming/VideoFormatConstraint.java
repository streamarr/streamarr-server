package com.streamarr.server.services.streaming;

/** Permitted video operations for a format attempt; audio negotiation remains independent. */
public enum VideoFormatConstraint {
  COPY_OR_ENCODE,
  ENCODE_ONLY
}
