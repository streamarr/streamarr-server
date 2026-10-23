package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.WorkerRegistration;
import java.util.Collection;
import lombok.Builder;
import lombok.NonNull;

/**
 * The probe capacities that one worker session grants. No probe holds a particular unit: the
 * background units in use are the background probes plus the playback probes beyond the playback
 * probe capacity, the formula that the worker also uses.
 */
@Builder(toBuilder = true)
record GrantedProbeCapacities(
    int playbackProbeCapacity,
    int backgroundProbeCapacity,
    int keyframePacketScanCapacity,
    @NonNull BackgroundProbeCapacityControl backgroundProbeCapacityControl) {

  static GrantedProbeCapacities of(WorkerRegistration registration) {
    return builder()
        .playbackProbeCapacity(registration.getPlaybackProbeCapacity())
        .backgroundProbeCapacity(registration.getBackgroundProbeCapacity())
        .keyframePacketScanCapacity(registration.getKeyframePacketScanCapacity())
        .backgroundProbeCapacityControl(registration.getBackgroundProbeCapacityControl())
        .build();
  }

  // The playback probe capacity and the control stay as the worker registered them.
  GrantedProbeCapacities changedTo(ProbeCapacityChanged changed) {
    return toBuilder()
        .backgroundProbeCapacity(changed.getBackgroundProbeCapacity())
        .keyframePacketScanCapacity(changed.getKeyframePacketScanCapacity())
        .build();
  }

  boolean fits(ProbeKind kind, Collection<ProbeKind> running) {
    var inUse = InUse.of(running);
    return switch (kind) {
      case PLAYBACK -> inUse.playback() < playbackProbeCapacity || freeBackgroundUnits(inUse) > 0;
      case BACKGROUND -> freeBackgroundUnits(inUse) > 0;
      case KEYFRAME_PACKET_SCAN -> inUse.keyframePacketScans() < keyframePacketScanCapacity;
    };
  }

  int units(ProbeKind kind) {
    return switch (kind) {
      case PLAYBACK -> playbackProbeCapacity;
      case BACKGROUND -> backgroundProbeCapacity;
      case KEYFRAME_PACKET_SCAN -> keyframePacketScanCapacity;
    };
  }

  /** Whether the worker has a unit that only a playback probe may take free. */
  boolean hasFreePlaybackUnit(Collection<ProbeKind> running) {
    return InUse.of(running).playback() < playbackProbeCapacity;
  }

  ProbeCapacity capacity(Collection<ProbeKind> running) {
    var inUse = InUse.of(running);
    return ProbeCapacity.builder()
        .playbackProbeCapacity(playbackProbeCapacity)
        .backgroundProbeCapacity(backgroundProbeCapacity)
        .keyframePacketScanCapacity(keyframePacketScanCapacity)
        .freePlaybackProbeUnits(Math.max(0, playbackProbeCapacity - inUse.playback()))
        .freeBackgroundProbeUnits(freeBackgroundUnits(inUse))
        .freeKeyframePacketScanUnits(
            Math.max(0, keyframePacketScanCapacity - inUse.keyframePacketScans()))
        .runningProbes(inUse.playback() + inUse.background())
        .runningPlaybackProbes(inUse.playback())
        .runningKeyframePacketScans(inUse.keyframePacketScans())
        .build();
  }

  private int freeBackgroundUnits(InUse inUse) {
    var used = inUse.background() + Math.max(0, inUse.playback() - playbackProbeCapacity);
    return Math.max(0, backgroundProbeCapacity - used);
  }

  private record InUse(int playback, int background, int keyframePacketScans) {

    private static InUse of(Collection<ProbeKind> running) {
      return new InUse(
          count(running, ProbeKind.PLAYBACK),
          count(running, ProbeKind.BACKGROUND),
          count(running, ProbeKind.KEYFRAME_PACKET_SCAN));
    }

    private static int count(Collection<ProbeKind> running, ProbeKind kind) {
      return (int) running.stream().filter(kind::equals).count();
    }
  }
}
