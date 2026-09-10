package com.streamarr.server.domain.media;

import jakarta.persistence.Embeddable;
import lombok.Builder;
import lombok.NonNull;

/**
 * Artwork-derived colors: the four quadrant averages and the vibrant primary are always present;
 * each target swatch is null when the artwork has no color in that profile.
 */
@Embeddable
@Builder
public record AmbientColors(
    @NonNull String topLeft,
    @NonNull String topRight,
    @NonNull String bottomRight,
    @NonNull String bottomLeft,
    @NonNull String primary,
    String darkVibrant,
    String darkMuted,
    String lightVibrant,
    String lightMuted) {}
