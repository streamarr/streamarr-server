package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * A Dolby Vision profile whose RPUs the player applies, with the highest Dolby Vision level that
 * the player accepts for that profile. The Dolby Vision level is separate from the codec level.
 */
@Builder
public record DolbyVisionProfileCapability(@NonNull DolbyVisionProfile profile, int maxLevel) {}
