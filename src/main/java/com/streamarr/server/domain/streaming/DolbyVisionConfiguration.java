package com.streamarr.server.domain.streaming;

import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * A Dolby Vision configuration record (dvcC or dvvC), kept whole. Every value in it is a claim. A
 * numeric value that the record does not state is empty, never zero, because zero is a real
 * base-layer compatibility identifier.
 */
@Builder
public record DolbyVisionConfiguration(
    @NonNull OptionalInt profile,
    @NonNull OptionalInt level,
    @NonNull OptionalInt baseLayerCompatibilityId,
    boolean baseLayer,
    boolean enhancementLayer,
    boolean rpu) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class DolbyVisionConfigurationBuilder {
    private OptionalInt profile = OptionalInt.empty();
    private OptionalInt level = OptionalInt.empty();
    private OptionalInt baseLayerCompatibilityId = OptionalInt.empty();
  }
}
