package com.streamarr.server.domain.streaming;

/**
 * The standard picture format that a player could use without a video stream's advanced HDR
 * information (ADR 0036). The probe derives it from the signaled transfer and the Dolby Vision
 * claim.
 */
public enum CompatibleBase {
  /** The probe could not read the transfer and found no Dolby Vision claim. */
  UNSPECIFIED,
  /** Dolby Vision declares no standard base, or the transfer is unspecified beside Dolby Vision. */
  NONE,
  SDR,
  /** SMPTE ST 2084, which covers HDR10 and PQ10. */
  PQ,
  /** ARIB STD-B67. */
  HLG
}
