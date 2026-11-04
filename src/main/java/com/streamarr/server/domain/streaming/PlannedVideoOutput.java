package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The video that a transcode decision plans to deliver: a stream copy or an encode (ADR 0013). Its
 * dynamic range never holds an UNSPECIFIED compatible base, HDR10+ presence or Dolby Vision
 * presence (ADR 0036). A copy that keeps Dolby Vision keeps the source's Dolby Vision facts as the
 * probe stored them, including RPU observations that the probe could not make, because the copy
 * passes those RPUs through unchanged.
 */
public sealed interface PlannedVideoOutput {

  VideoDynamicRange dynamicRange();

  private static void requireSpecified(VideoDynamicRange range) {
    if (range.compatibleBase() == CompatibleBase.UNSPECIFIED
        || range.hdr10Plus() == Presence.UNSPECIFIED
        || range.dolbyVision() instanceof DolbyVision.Unspecified) {
      throw new IllegalArgumentException(
          "A planned output's dynamic range cannot hold an UNSPECIFIED value");
    }
  }

  /**
   * A stream copy of the chosen video stream. {@code codecProfile} and {@code codecLevel} are what
   * probe version 5 and later report, and are empty for a probe version 4 outcome. {@code
   * bitstreamRewrites} lists every change that the worker makes to the copied stream. {@code
   * masteringDisplay} and {@code contentLightLevel} are the static HDR metadata that the worker
   * writes into the copy's hvcC record. Only a copy with a PQ output holds them, and only the
   * values that the probe stored (ADR 0036).
   */
  @Builder
  record StreamCopy(
      @NonNull VideoDynamicRange dynamicRange,
      @NonNull Optional<VideoCodecProfile> codecProfile,
      @NonNull OptionalInt codecLevel,
      @NonNull List<BitstreamRewrite> bitstreamRewrites,
      @NonNull Optional<MasteringDisplayColorVolume> masteringDisplay,
      @NonNull Optional<ContentLightLevel> contentLightLevel)
      implements PlannedVideoOutput {

    public StreamCopy {
      requireSpecified(dynamicRange);
      bitstreamRewrites = List.copyOf(bitstreamRewrites);
    }

    /** The copy of the chosen video stream with the bitstream rewrites. */
    public static StreamCopy of(
        @NonNull VideoPlaybackFacts source, @NonNull List<BitstreamRewrite> bitstreamRewrites) {
      var properties = source.videoProperties();
      var range = plannedRange(source.dynamicRange().afterRewrites(bitstreamRewrites));
      var copy =
          StreamCopy.builder()
              .dynamicRange(range)
              .codecProfile(properties.flatMap(VideoStreamProperties::codecProfile))
              .codecLevel(
                  properties.map(VideoStreamProperties::codecLevel).orElseGet(OptionalInt::empty))
              .bitstreamRewrites(bitstreamRewrites);
      if (range.compatibleBase() == CompatibleBase.PQ) {
        copy.masteringDisplay(source.masteringDisplay())
            .contentLightLevel(source.contentLightLevel());
      }

      return copy.build();
    }

    /**
     * Whether only a worker that checks a copy against its planned output can deliver this copy: a
     * copy whose output is not SDR, or that lists a rewrite. An SDR copy without rewrites passes
     * the source through, so any worker delivers it (ADR 0013).
     */
    public boolean requiresPlannedOutputSupport() {
      return dynamicRange.compatibleBase() != CompatibleBase.SDR || !bitstreamRewrites.isEmpty();
    }

    // A copy of a source whose compatible base is UNSPECIFIED plans SDR (ADR 0013). HDR10+ or
    // Dolby Vision that the probe could not check plans as absent, as the playlist announces it.
    private static VideoDynamicRange plannedRange(VideoDynamicRange source) {
      var base = source.compatibleBase();
      if (base == CompatibleBase.UNSPECIFIED) {
        base = CompatibleBase.SDR;
      }

      var hdr10Plus = source.hdr10Plus();
      if (hdr10Plus == Presence.UNSPECIFIED) {
        hdr10Plus = Presence.ABSENT;
      }

      var dolbyVision = source.dolbyVision();
      if (dolbyVision instanceof DolbyVision.Unspecified) {
        dolbyVision = new DolbyVision.Absent();
      }

      return new VideoDynamicRange(base, hdr10Plus, dolbyVision);
    }

    // Lombok builder defaults — its generated setters assign them
    @SuppressWarnings({"java:S1068", "java:S9438"})
    public static class StreamCopyBuilder {
      private Optional<MasteringDisplayColorVolume> masteringDisplay = Optional.empty();
      private Optional<ContentLightLevel> contentLightLevel = Optional.empty();
    }
  }

  /** An encode, in the codec profile that it forces. */
  record Encode(
      @NonNull Optional<EncodeCodecProfile> codecProfile, @NonNull VideoDynamicRange dynamicRange)
      implements PlannedVideoOutput {

    public Encode {
      requireSpecified(dynamicRange);
    }

    /** Every encode is SDR until the server encodes HDR (#397). */
    public static Encode sdr(@NonNull Optional<EncodeCodecProfile> codecProfile) {
      return new Encode(codecProfile, VideoDynamicRange.SDR);
    }
  }
}
