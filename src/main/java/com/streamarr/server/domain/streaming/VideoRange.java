package com.streamarr.server.domain.streaming;

/** The dynamic range of HLS video, as a playlist's VIDEO-RANGE attribute names it (RFC 8216). */
public enum VideoRange {
  SDR,
  /** SMPTE ST 2084, which covers HDR10, PQ10 and the PQ base of Dolby Vision. */
  PQ,
  /** Hybrid log-gamma, ARIB STD-B67. */
  HLG;

  /**
   * The range of video that keeps the source's compatible base. Dolby Vision without a compatible
   * base is PQ, and an unspecified base is SDR.
   */
  public static VideoRange ofCompatibleBase(CompatibleBase base) {
    return switch (base) {
      case SDR, UNSPECIFIED -> SDR;
      case PQ, NONE -> PQ;
      case HLG -> HLG;
    };
  }
}
