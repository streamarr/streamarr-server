package com.streamarr.server.domain.task;

/**
 * The scan scheduler's claim priority of a file's bitrate scan (ADR 0054). Keyframe packet scans
 * keep db-scheduler's medium priority, 50, so the scheduler claims them before any bitrate scan.
 */
public enum BitrateScanPriority {
  /** The chosen video stream has a probed bitrate, such as one from trusted statistics tags. */
  PROBED_BITRATE(20),
  /** The chosen video stream has no probed bitrate, so its copy's cost is the coarsest. */
  UNPROBED_BITRATE(30),
  /**
   * A stream session copied the file's video without a measurement, so the scan runs before every
   * other bitrate scan and the viewer's next stream session of the file gets the measured cost.
   */
  COPIED_BY_STREAM_SESSION(40);

  private final int value;

  BitrateScanPriority(int value) {
    this.value = value;
  }

  public int value() {
    return value;
  }

  public static BitrateScanPriority of(boolean chosenVideoHasProbedBitrate) {
    if (chosenVideoHasProbedBitrate) {
      return PROBED_BITRATE;
    }

    return UNPROBED_BITRATE;
  }
}
