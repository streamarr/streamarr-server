package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;

@FunctionalInterface
public interface KeyframePacketScanner {

  /**
   * Probes the source like {@link FfprobeService#probe}, but finds the keyframe positions by
   * scanning every packet of the chosen video stream instead of reading the container index.
   *
   * @throws com.streamarr.server.exceptions.ProbeWorkersBusyException when every compatible
   *     worker's slots are in use, so the scan did not start
   */
  ProbeOutcome scanKeyframePackets(ProbeExecutionRequest request);
}
