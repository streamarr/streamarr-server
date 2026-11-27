package com.streamarr.server.domain.streaming;

/**
 * The matrix with which an encode that does not tone-map reads a video stream that names no matrix
 * (ADR 0013).
 */
public enum AssumedSourceMatrix {
  /** Rec. ITU-R BT.709, the matrix of high-definition video. */
  BT709,
  /** Rec. ITU-R BT.601, the matrix of standard-definition video. */
  BT601
}
