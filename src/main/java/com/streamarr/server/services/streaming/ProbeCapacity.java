package com.streamarr.server.services.streaming;

import lombok.Builder;

/**
 * The probe capacity of the connected workers that can run the server's probes.
 *
 * @param advertised the probes those workers run at once, in total
 * @param running the probes running on those workers, keyframe packet scans included
 * @param runningPacketScans the keyframe packet scans among the running probes
 */
@Builder
public record ProbeCapacity(int advertised, int running, int runningPacketScans) {

  /**
   * The probes that may start beside {@code probeAttemptsInProgress}, the server's probe attempts
   * that started and have not ended, whether or not they reached a worker yet.
   */
  public int free(int probeAttemptsInProgress) {
    var used = Math.max(running, probeAttemptsInProgress + runningPacketScans);
    return Math.max(0, advertised - used);
  }
}
