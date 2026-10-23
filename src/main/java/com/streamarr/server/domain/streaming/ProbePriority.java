package com.streamarr.server.domain.streaming;

/** Whether a playback request waits for a probe, which decides the worker capacity it uses. */
public enum ProbePriority {
  /** A playback request waits for the probe. */
  PLAYBACK,
  /** No playback request waits for the probe, as for a probe that a scan requested. */
  BACKGROUND
}
