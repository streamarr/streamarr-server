package com.streamarr.server.services.metadata.color;

import java.util.Objects;
import lombok.Builder;

@Builder
public record AmbientColors(
    String topLeft, String topRight, String bottomRight, String bottomLeft, String primary) {

  public AmbientColors {
    Objects.requireNonNull(topLeft, "topLeft");
    Objects.requireNonNull(topRight, "topRight");
    Objects.requireNonNull(bottomRight, "bottomRight");
    Objects.requireNonNull(bottomLeft, "bottomLeft");
    Objects.requireNonNull(primary, "primary");
  }
}
