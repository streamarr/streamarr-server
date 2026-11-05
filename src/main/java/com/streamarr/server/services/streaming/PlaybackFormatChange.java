package com.streamarr.server.services.streaming;

/**
 * Why a playback session plays another output than its requested playback choice asked for (ADR
 * 0052). Auto's recoveries never set one.
 */
public enum PlaybackFormatChange {
  /** An HLS copy failed, so an encode plays. */
  COPY_FAILED,
  /** Direct play failed and the server rules out the HLS copy, so an encode plays. */
  DIRECT_PLAY_FAILED,
  /** Original could not copy the source, so its first format attempt is an encode. */
  STARTED_WITH_ENCODE,
  /**
   * A Dolby Vision copy failed, so the same copy with Dolby Vision stripped plays on the source's
   * PQ or HLG base, and the playback choice stays.
   */
  DOLBY_VISION_STRIPPED
}
