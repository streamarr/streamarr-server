package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * The Dolby Vision of a stream copy that the playlist describes as Dolby Vision (ADR 0013): its
 * profile, and the Dolby Vision level that the decision set. The copy's Dolby Vision configuration
 * record holds both.
 */
public record DolbyVisionOutput(@NonNull DolbyVisionProfile profile, int level) {

  public DolbyVisionOutput {
    if (!DolbyVisionLevelTable.isTableLevel(level)) {
      throw new IllegalArgumentException("A Dolby Vision output has a level from 1 to 13");
    }
  }
}
