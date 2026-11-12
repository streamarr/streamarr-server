package com.streamarr.server.services.streaming;

/**
 * Why a playback session plays another output than its requested playback choice asked for (ADR
 * 0052). Of Auto's recoveries, only one that gives an HDR range up for SDR sets one.
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
  DOLBY_VISION_STRIPPED,
  /**
   * The viewer switched audio tracks during direct play, and the server rules out the HLS copy, so
   * an encode plays.
   */
  AUDIO_TRACK_SWITCHED,
  /**
   * A format attempt plays SDR of a PQ or HLG source after the viewer chose HDR in a fixed quality,
   * or after an earlier format attempt of the playback session played PQ or HLG (ADR 0013). It is
   * set only while no other change is set.
   */
  HDR_NOT_KEPT
}
