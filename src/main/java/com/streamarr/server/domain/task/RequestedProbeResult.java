package com.streamarr.server.domain.task;

/** How the probe that a scan requested for one media file ended, or that it has not ended. */
public enum RequestedProbeResult {
  /** A successful outcome matches the requested inputs, so the file is ready for playback. */
  READY,
  /** A terminal media error matches the requested inputs. */
  MEDIA_ERROR,
  /** The latest attempt at the requested inputs failed; the probe retries in the background. */
  FAILED,
  /** The file changed, and a probe of the later inputs replaced the requested one. */
  SUPERSEDED,
  /** A newer probe version already stored an outcome for the same source. */
  PROBED_BY_NEWER_VERSION,
  /** The media file or its source no longer exists. */
  REMOVED,
  /** No attempt at the requested inputs has finished. */
  PENDING
}
