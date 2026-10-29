package com.streamarr.server.domain.streaming;

/** A video stream's transfer characteristics, named after their ITU-T H.273 code points. */
public enum TransferCharacteristics {
  BT709,
  BT470M,
  BT470BG,
  SMPTE170M,
  SMPTE240M,
  LINEAR,
  LOG100,
  LOG316,
  /** xvYCC. */
  IEC61966_2_4,
  BT1361,
  /** sRGB. */
  IEC61966_2_1,
  BT2020_10,
  BT2020_12,
  /** The perceptual quantizer. */
  SMPTE2084,
  SMPTE428,
  /** Hybrid log-gamma. */
  ARIB_STD_B67
}
