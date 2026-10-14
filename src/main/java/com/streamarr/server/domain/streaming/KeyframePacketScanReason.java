package com.streamarr.server.domain.streaming;

/** Why the server must scan a source's packets to learn its keyframe positions. */
public enum KeyframePacketScanReason {
  /** The container has no index, as in MPEG-TS, AVI and fragmented MP4 sources. */
  NO_CONTAINER_INDEX,
  /** The container index skips keyframes, as sparse Matroska Cues do. */
  INCOMPLETE_CONTAINER_INDEX,
  /** The worker cannot read the last keyframe that the container index lists. */
  UNREADABLE_LAST_INDEXED_KEYFRAME
}
