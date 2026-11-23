package com.streamarr.server.services.playbackreport;

/** The dynamic range that a player or display reports for what it shows. */
public enum PlayerHdrMode {
  SDR,
  /** High dynamic range in a format that the player or display does not name. */
  HDR,
  HDR10,
  HDR10_PLUS,
  HLG,
  DOLBY_VISION
}
