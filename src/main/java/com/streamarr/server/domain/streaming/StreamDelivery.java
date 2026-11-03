package com.streamarr.server.domain.streaming;

/** How a stream session sends media to the player. */
public enum StreamDelivery {
  /** The server sends HLS segments that job attempts produce. */
  HLS,
  /** The server sends the source file itself (ADR 0045). */
  DIRECT_PLAY
}
