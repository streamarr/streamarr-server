package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.ProbeOutcome;
import lombok.Builder;
import lombok.NonNull;

/**
 * A stored probe outcome with the source snapshot and probe version that it was produced for.
 * {@code keyframePacketScanOwed} says whether a keyframe packet scan must still run for it.
 */
@Builder
public record PersistedProbeOutcome(
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion,
    @NonNull ProbeOutcome outcome,
    boolean keyframePacketScanOwed) {

  /** A stored outcome that owes a keyframe packet scan exactly when its probe asked for one. */
  public PersistedProbeOutcome(
      SourceFileSnapshot snapshot, int probeVersion, ProbeOutcome outcome) {
    this(snapshot, probeVersion, outcome, outcome.needsKeyframePacketScan());
  }

  public boolean matches(SourceFileSnapshot requestedSnapshot, int requestedVersion) {
    return snapshot.equals(requestedSnapshot) && probeVersion == requestedVersion;
  }

  public boolean isCompatibleWith(int currentVersion) {
    return probeVersion <= currentVersion;
  }
}
