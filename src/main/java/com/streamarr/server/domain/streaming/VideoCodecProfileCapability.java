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

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class VideoCodecProfileCapabilityBuilder {
    private Optional<VideoCodecTier> maxTier = Optional.empty();
  }
}
