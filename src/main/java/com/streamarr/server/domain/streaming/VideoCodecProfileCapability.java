package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * One codec profile of a video capability entry, with the maximum codec level and codec tier that
 * the player accepts for that profile alone (ADR 0049). {@code maxLevel} is major × 10 + minor, as
 * the probe numbers levels. {@code maxTier} is empty for H.264, which has no tier.
 */
@Builder
public record VideoCodecProfileCapability(
    @NonNull VideoCodecProfile profile, int maxLevel, @NonNull Optional<VideoCodecTier> maxTier) {

  // Lombok skips its own builder() because this one exists.
  public static VideoCodecProfileCapabilityBuilder builder() {
    return new VideoCodecProfileCapabilityBuilder().maxTier(Optional.empty());
  }
}
