package com.streamarr.server.domain.streaming;

/** The dynamic range of HLS video, as a playlist's VIDEO-RANGE attribute names it (RFC 8216). */
public enum VideoRange {
  SDR,
  /** SMPTE ST 2084, which covers HDR10, PQ10 and the PQ base of Dolby Vision. */
  PQ,
  /** Hybrid log-gamma, ARIB STD-B67. */
  HLG
}
