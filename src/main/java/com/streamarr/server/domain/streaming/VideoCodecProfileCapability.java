package com.streamarr.server.domain.streaming;

import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * One codec profile of a video capability entry, with the maximum codec level that the player
 * accepts for that profile alone (ADR 0049). {@code maxLevel} is major × 10 + minor, as the probe
 * numbers levels.
 */
@Builder
public record VideoCodecProfileCapability(@NonNull VideoCodecProfile profile, int maxLevel) {

  /** Whether a stream of this profile fits the maximum level. An unknown level fits. */
  boolean acceptsLevel(OptionalInt level) {
    return level.isEmpty() || level.getAsInt() <= maxLevel;
  }
}
