package com.streamarr.server.domain.streaming;

/** Where the worker found a source's keyframe positions. */
public enum KeyframePositionSource {
  /** The MP4 or MOV sync-sample table, or the Matroska Cues element. */
  CONTAINER_INDEX,
  /** A scan of every packet of the chosen video stream. */
  PACKET_SCAN
}
