package com.streamarr.server.domain.media;

import jakarta.persistence.Embeddable;
import lombok.Builder;
import lombok.NonNull;

@Embeddable
@Builder
public record AmbientColors(
    @NonNull String topLeft,
    @NonNull String topRight,
    @NonNull String bottomRight,
    @NonNull String bottomLeft,
    @NonNull String primary) {}
