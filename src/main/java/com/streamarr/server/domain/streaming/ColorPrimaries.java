package com.streamarr.server.domain.streaming;

/** A video stream's color primaries, named after their ITU-T H.273 code points. */
public enum ColorPrimaries {
  BT709,
  BT470M,
  BT470BG,
  SMPTE170M,
  SMPTE240M,
  FILM,
  /** BT.2020 and BT.2100. */
  BT2020,
  SMPTE428,
  /** DCI-P3. */
  SMPTE431,
  /** Display P3. */
  SMPTE432,
  EBU3213
}
