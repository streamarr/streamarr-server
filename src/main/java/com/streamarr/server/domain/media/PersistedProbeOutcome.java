package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.ProbeOutcome;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PersistedProbeOutcome(
    @NonNull SourceFileSnapshot snapshot, int probeVersion, @NonNull ProbeOutcome outcome) {

  public boolean matches(SourceFileSnapshot requestedSnapshot, int requestedVersion) {
    return snapshot.equals(requestedSnapshot) && probeVersion == requestedVersion;
  }

  public boolean isCompatibleWith(int currentVersion) {
    return probeVersion <= currentVersion;
  }
}
