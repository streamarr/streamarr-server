package com.streamarr.server.domain.streaming;

/**
 * An output beyond 8-bit 4:2:0 SDR that a worker-verified encoder writes after its own test encode
 * passed (#397).
 */
public enum VideoEncodeFeature {
  /** 10-bit 4:2:0 video. */
  TEN_BIT,
  /** 10-bit video with the PQ transfer, BT.2020 colour and HDR10 static metadata. */
  PQ,
  /** 10-bit video with the HLG transfer and BT.2020 colour. */
  HLG,
  /**
   * Dolby Vision without a compatible base, profiles 5 and 10.0, reshaped with each frame's RPU
   * into PQ before the encoder.
   */
  DOLBY_VISION_TO_PQ
}
