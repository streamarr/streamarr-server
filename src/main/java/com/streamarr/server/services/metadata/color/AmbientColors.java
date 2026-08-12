package com.streamarr.server.services.metadata.color;

import lombok.Builder;

@Builder
public record AmbientColors(
    String topLeft, String topRight, String bottomRight, String bottomLeft, String primary) {}
