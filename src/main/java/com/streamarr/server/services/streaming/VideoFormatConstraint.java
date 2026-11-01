package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import lombok.NonNull;

/** Permitted video operations for a format attempt; audio negotiation remains independent. */
public enum VideoFormatConstraint {
  COPY_OR_ENCODE,
  /**
   * Original's constraint. It still encodes an interlaced source, which no copy may carry
   * (streamarr/streamarr-server#653), on Auto's ladder (ADR 0052).
   */
  COPY_ONLY,
  ENCODE_ONLY;

  /** Whether the format attempt may encode the source's video. */
  boolean permitsEncode(@NonNull MediaProbe source) {
    return switch (this) {
      case COPY_OR_ENCODE, ENCODE_ONLY -> true;
      case COPY_ONLY -> source.isInterlaced();
    };
  }
}
