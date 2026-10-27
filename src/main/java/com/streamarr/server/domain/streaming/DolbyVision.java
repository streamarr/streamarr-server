package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.NonNull;

/** What a probe found out about Dolby Vision in one video stream (ADR 0036). */
public sealed interface DolbyVision {

  /** The probe could not check for Dolby Vision. */
  record Unspecified() implements DolbyVision {}

  /** The probe checked the record, the sample entry and the samples, and found no Dolby Vision. */
  record Absent() implements DolbyVision {}

  /**
   * The probe found a configuration record, a Dolby Vision sample entry or an RPU. {@code declared}
   * is empty when the probe found no configuration record. {@code firstRpuHeader} is empty unless
   * {@code observedRpu} is PRESENT. {@code rpuStartsSample} is PRESENT when a sample that the probe
   * read starts with an RPU ahead of its picture, the layout of a muxer that starts a new sample at
   * each RPU. Probe versions before 5 report neither.
   */
  record Present(
      @NonNull Optional<DolbyVisionConfiguration> declared,
      @NonNull Presence observedRpu,
      @NonNull Optional<DolbyVisionRpuHeader> firstRpuHeader,
      @NonNull Presence rpuStartsSample)
      implements DolbyVision {

    /** What a probe that reads no RPU header and no sample layout reports. */
    public Present(
        @NonNull Optional<DolbyVisionConfiguration> declared, @NonNull Presence observedRpu) {
      this(declared, observedRpu, Optional.empty(), Presence.UNSPECIFIED);
    }
  }
}
