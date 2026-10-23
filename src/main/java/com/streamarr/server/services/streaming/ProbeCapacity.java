package com.streamarr.server.services.streaming;

import lombok.Builder;

/**
 * The probe capacities that connected workers grant, and the units of them that no running probe
 * uses. Each worker computes its own free units, since a playback probe beyond the playback probe
 * capacity uses one of that worker's background units.
 *
 * @param backgroundProbeCapacity the background probes that those workers run at once, in total
 * @param freePlaybackProbeUnits the units that only a playback probe may take
 * @param freeBackgroundProbeUnits the units that any probe other than a keyframe packet scan may
 *     take
 * @param freeKeyframePacketScanUnits the units that only a keyframe packet scan may take
 * @param runningProbes the probes other than keyframe packet scans that those workers run
 * @param runningKeyframePacketScans the keyframe packet scans that those workers run
 */
@Builder
public record ProbeCapacity(
    int backgroundProbeCapacity,
    int freePlaybackProbeUnits,
    int freeBackgroundProbeUnits,
    int freeKeyframePacketScanUnits,
    int runningProbes,
    int runningKeyframePacketScans) {

  public static final ProbeCapacity NONE = builder().build();

  public ProbeCapacity plus(ProbeCapacity other) {
    return builder()
        .backgroundProbeCapacity(backgroundProbeCapacity + other.backgroundProbeCapacity)
        .freePlaybackProbeUnits(freePlaybackProbeUnits + other.freePlaybackProbeUnits)
        .freeBackgroundProbeUnits(freeBackgroundProbeUnits + other.freeBackgroundProbeUnits)
        .freeKeyframePacketScanUnits(
            freeKeyframePacketScanUnits + other.freeKeyframePacketScanUnits)
        .runningProbes(runningProbes + other.runningProbes)
        .runningKeyframePacketScans(runningKeyframePacketScans + other.runningKeyframePacketScans)
        .build();
  }

  /**
   * The probes that the server may claim beside {@code probeExecutionsInProgress}, its probe
   * executions that started and have not ended. An execution that no worker runs yet could take a
   * unit of either kind, so it counts against both.
   */
  public ProbeClaim probesToClaim(int probeExecutionsInProgress) {
    var awaitingDispatch = Math.max(0, probeExecutionsInProgress - runningProbes);
    return ProbeClaim.builder()
        .backgroundProbes(Math.max(0, freeBackgroundProbeUnits - awaitingDispatch))
        .playbackProbes(Math.max(0, freePlaybackProbeUnits - awaitingDispatch))
        .build();
  }

  /** The keyframe packet scans that the server may claim beside its scans in progress. */
  public int keyframePacketScansToClaim(int scanExecutionsInProgress) {
    var awaitingDispatch = Math.max(0, scanExecutionsInProgress - runningKeyframePacketScans);
    return Math.max(0, freeKeyframePacketScanUnits - awaitingDispatch);
  }
}
