package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
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

  /** Whether a stream of this profile fits these maximums. An unknown level or tier fits. */
  boolean acceptsLevelAndTier(OptionalInt level, Optional<VideoCodecTier> tier) {
    var levelFits = level.isEmpty() || level.getAsInt() <= maxLevel;
    return levelFits && tier.map(this::acceptsTier).orElse(true);
  }

  private boolean acceptsTier(VideoCodecTier tier) {
    return maxTier.filter(tier::isAtMost).isPresent();
  }

  // Lombok skips its own builder() because this one exists.
  public static VideoCodecProfileCapabilityBuilder builder() {
    return new VideoCodecProfileCapabilityBuilder().maxTier(Optional.empty());
  }
}
