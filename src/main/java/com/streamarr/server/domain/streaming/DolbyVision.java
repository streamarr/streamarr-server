package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.NonNull;

/** What a probe found out about Dolby Vision in one video stream (ADR 0036). */
public sealed interface DolbyVision {

  /** The probe could not check for Dolby Vision. */
  record Unspecified() implements DolbyVision {}

  /** The probe checked the record, the sample entry and the samples, and found no Dolby Vision. */
  record Absent() implements DolbyVision {}

  /**
   * The probe found a configuration record, a Dolby Vision sample entry or an RPU. {@code declared}
   * is empty when the probe found no configuration record. {@code firstRpuHeader} is empty and
   * {@code rpuPlacement} is UNSPECIFIED unless {@code observedRpu} is PRESENT. Probe versions
   * before 5 report neither.
   */
  record Present(
      @NonNull Optional<DolbyVisionConfiguration> declared,
      @NonNull Presence observedRpu,
      @NonNull Optional<DolbyVisionRpuHeader> firstRpuHeader,
      @NonNull DolbyVisionRpuPlacement rpuPlacement)
      implements DolbyVision {

    /** What a probe that reads no RPU header and no RPU placement reports. */
    public Present(
        @NonNull Optional<DolbyVisionConfiguration> declared, @NonNull Presence observedRpu) {
      this(declared, observedRpu, Optional.empty(), DolbyVisionRpuPlacement.UNSPECIFIED);
    }

    /**
     * This Dolby Vision after a copy converts single-track profile 7 to profile 8.1 (ADR 0036,
     * streamarr/streamarr-server#651): a record of profile 8 with compatibility identifier 1, a
     * base layer and an RPU at the level that the source's record names, an RPU header without an
     * enhancement layer or residual, and each RPU at the end of its own picture's sample.
     */
    public Present convertedToProfile81() {
      var record =
          DolbyVisionConfiguration.builder()
              .profile(OptionalInt.of(8))
              .level(declared.map(DolbyVisionConfiguration::level).orElse(OptionalInt.empty()))
              .baseLayerCompatibilityId(OptionalInt.of(1))
              .baseLayer(true)
              .rpu(true)
              .build();
      return new Present(
          Optional.of(record),
          Presence.PRESENT,
          firstRpuHeader.map(Present::withoutEnhancementLayer),
          DolbyVisionRpuPlacement.AFTER_OWN_PICTURE);
    }

    private static DolbyVisionRpuHeader withoutEnhancementLayer(DolbyVisionRpuHeader header) {
      return DolbyVisionRpuHeader.builder()
          .vdrRpuProfile(1)
          .baseLayerFullRange(header.baseLayerFullRange())
          .disableResidual(true)
          .vdrBitDepth(header.vdrBitDepth())
          .build();
    }
  }
}
