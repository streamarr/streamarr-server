package com.streamarr.server.domain.streaming;

/** Why a worker cannot report usable keyframe positions for a source. */
public enum KeyframesUnavailableReason {
  /** A keyframe has no presentation time or no decode time. */
  KEYFRAME_WITHOUT_TIMESTAMP,
  /** A packet scan found no keyframe in the chosen video stream. */
  NO_KEYFRAMES,
  /** A packet scan ended in a terminal media error, although the probe had read the source. */
  PACKET_SCAN_MEDIA_ERROR,
  /** Every packet scan of the source timed out or reported no keyframe result. */
  PACKET_SCAN_FAILED;

  /**
   * Whether a packet scan failed, so that it says nothing about the stream's keyframes. The other
   * reasons say what a probe or a packet scan read in the stream.
   */
  public boolean isFailedPacketScan() {
    return switch (this) {
      case PACKET_SCAN_MEDIA_ERROR, PACKET_SCAN_FAILED -> true;
      case KEYFRAME_WITHOUT_TIMESTAMP, NO_KEYFRAMES -> false;
    };
  }
}
