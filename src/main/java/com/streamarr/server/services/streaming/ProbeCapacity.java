package com.streamarr.server.services.streaming;

import lombok.Builder;

/**
 * The probe capacities that connected workers grant, and the units of them that no running probe
 * uses. Each worker computes its own free units, since a playback probe beyond the playback probe
 * capacity uses one of that worker's background units.
 *
 * @param playbackProbeCapacity the playback probes that those workers run at once in their own
 *     units, in total
 * @param backgroundProbeCapacity the background probes that those workers run at once, in total
 * @param keyframePacketScanCapacity the keyframe packet scans that those workers run at once, in
 *     total
 * @param freePlaybackProbeUnits the units that only a playback probe may take
 * @param freeBackgroundProbeUnits the units that any probe other than a keyframe packet scan may
 *     take
 * @param freeKeyframePacketScanUnits the units that only a keyframe packet scan may take
 * @param runningProbes the probes other than keyframe packet scans that those workers run
 * @param runningPlaybackProbes the playback probes among the running probes
 * @param runningKeyframePacketScans the keyframe packet scans that those workers run
 */
@Builder(toBuilder = true)
public record ProbeCapacity(
    int playbackProbeCapacity,
    int backgroundProbeCapacity,
    int keyframePacketScanCapacity,
    int freePlaybackProbeUnits,
    int freeBackgroundProbeUnits,
    int freeKeyframePacketScanUnits,
    int runningProbes,
    int runningPlaybackProbes,
    int runningKeyframePacketScans) {

  public static final ProbeCapacity NONE = builder().build();

  public ProbeCapacity plus(ProbeCapacity other) {
    return builder()
        .playbackProbeCapacity(playbackProbeCapacity + other.playbackProbeCapacity)
        .backgroundProbeCapacity(backgroundProbeCapacity + other.backgroundProbeCapacity)
        .keyframePacketScanCapacity(keyframePacketScanCapacity + other.keyframePacketScanCapacity)
        .freePlaybackProbeUnits(freePlaybackProbeUnits + other.freePlaybackProbeUnits)
        .freeBackgroundProbeUnits(freeBackgroundProbeUnits + other.freeBackgroundProbeUnits)
        .freeKeyframePacketScanUnits(
            freeKeyframePacketScanUnits + other.freeKeyframePacketScanUnits)
        .runningProbes(runningProbes + other.runningProbes)
        .runningPlaybackProbes(runningPlaybackProbes + other.runningPlaybackProbes)
        .runningKeyframePacketScans(runningKeyframePacketScans + other.runningKeyframePacketScans)
        .build();
  }

  /**
   * The probes that the server may claim beside its executions in progress. An execution that no
   * worker runs yet still needs a unit: a background execution needs a background unit, and a
   * playback execution needs a playback unit, or a background unit once none is free.
   */
  public ProbeClaim probesToClaim(ProbeExecutionsInProgress inProgress) {
    var awaitingDispatch = Math.max(0, inProgress.executions() - runningProbes);
    var playbackAwaitingDispatch =
        Math.clamp(
            (long) inProgress.playbackExecutions() - runningPlaybackProbes, 0, awaitingDispatch);
    var backgroundAwaitingDispatch = awaitingDispatch - playbackAwaitingDispatch;
    var playbackBeyondFreeUnits = Math.max(0, playbackAwaitingDispatch - freePlaybackProbeUnits);
    return ProbeClaim.builder()
        .backgroundProbes(
            Math.max(
                0, freeBackgroundProbeUnits - backgroundAwaitingDispatch - playbackBeyondFreeUnits))
        .playbackProbes(Math.max(0, freePlaybackProbeUnits - playbackAwaitingDispatch))
        .build();
  }

  /** The keyframe packet scans that the server may claim beside its scans in progress. */
  public int keyframePacketScansToClaim(int scanExecutionsInProgress) {
    var awaitingDispatch = Math.max(0, scanExecutionsInProgress - runningKeyframePacketScans);
    return Math.max(0, freeKeyframePacketScanUnits - awaitingDispatch);
  }
}
