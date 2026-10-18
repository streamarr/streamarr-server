package com.streamarr.server.domain.streaming;

/** The basis on which ADR 0037's frame-rate rule chose the output frame rate. */
public enum OutputFrameRateBasis {
  /** The declared rates agree on a listed rate, and Matroska's statistics agree when present. */
  DECLARED_RATES,
  /** Matroska's statistics say that the stream runs faster than its scanned first 30 s. */
  MATROSKA_STATISTICS,
  /** The rule snapped the motion rate that the packet scan measured. */
  PACKET_SCAN,
  /** The scan found fewer than 2 frames, so the rule snapped a declared rate. */
  DECLARED_RATE_FALLBACK,
  /** The scan found fewer than 2 frames and neither declared rate is usable. */
  REFUSED
}
