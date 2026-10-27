package com.streamarr.server.domain.streaming;

/** A video stream's matrix coefficients, named after their ITU-T H.273 code points. */
public enum MatrixCoefficients {
  /** The identity matrix, as for RGB and GBR. */
  IDENTITY,
  BT709,
  FCC,
  BT470BG,
  SMPTE170M,
  SMPTE240M,
  YCGCO,
  BT2020_NCL,
  BT2020_CL,
  SMPTE2085,
  CHROMA_DERIVED_NCL,
  CHROMA_DERIVED_CL,
  ICTCP
}
