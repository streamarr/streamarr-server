package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbePriority;
import com.streamarr.transcode.v1.ProbeRequest;

/** The kind of a probe, which decides the worker capacity that the probe uses. */
enum ProbeKind {
  PLAYBACK,
  BACKGROUND,
  KEYFRAME_PACKET_SCAN;

  // A keyframe packet scan is never a playback probe, whatever its priority, as on the worker.
  static ProbeKind of(ProbeRequest request) {
    if (request.getScanKeyframePackets()) {
      return KEYFRAME_PACKET_SCAN;
    }

    if (request.getPriority() == ProbePriority.PROBE_PRIORITY_PLAYBACK) {
      return PLAYBACK;
    }

    return BACKGROUND;
  }
}
