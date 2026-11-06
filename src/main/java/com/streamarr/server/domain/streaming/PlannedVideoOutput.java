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
   * values that the probe stored (ADR 0036). {@code dolbyVisionOutput} is present only on a copy
   * that keeps Dolby Vision and that the playlist describes as Dolby Vision (ADR 0013). {@code
   * bitDepth} and {@code chromaSubsampling} are the source's, as the probe stored them.
   */
  @Builder(toBuilder = true)
  record StreamCopy(
      @NonNull VideoDynamicRange dynamicRange,
      @NonNull Optional<VideoCodecProfile> codecProfile,
      @NonNull OptionalInt codecLevel,
      @NonNull OptionalInt bitDepth,
      @NonNull Optional<ChromaSubsampling> chromaSubsampling,
      @NonNull List<BitstreamRewrite> bitstreamRewrites,
      @NonNull Optional<MasteringDisplayColorVolume> masteringDisplay,
      @NonNull Optional<ContentLightLevel> contentLightLevel,
      @NonNull Optional<DolbyVisionOutput> dolbyVisionOutput)
      implements PlannedVideoOutput {

    public StreamCopy {
      requireSpecified(dynamicRange);
      bitstreamRewrites = List.copyOf(bitstreamRewrites);
      if (dolbyVisionOutput.isPresent()
          && !(dynamicRange.dolbyVision() instanceof DolbyVision.Present)) {
        throw new IllegalArgumentException(
            "A planned copy without Dolby Vision cannot hold a Dolby Vision output");
      }
    }

    /** This copy, described as Dolby Vision with the output. */
    public StreamCopy withDolbyVisionOutput(@NonNull DolbyVisionOutput output) {
      return toBuilder().dolbyVisionOutput(Optional.of(output)).build();
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
              .bitDepth(source.bitDepth())
              .chromaSubsampling(source.chromaSubsampling())
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
      private OptionalInt bitDepth = OptionalInt.empty();
      private Optional<ChromaSubsampling> chromaSubsampling = Optional.empty();
      private Optional<MasteringDisplayColorVolume> masteringDisplay = Optional.empty();
      private Optional<ContentLightLevel> contentLightLevel = Optional.empty();
      private Optional<DolbyVisionOutput> dolbyVisionOutput = Optional.empty();
    }
  }

  /**
   * An encode, in the codec profile that it forces, with the bit depth, 8 or 10, and the chroma
   * subsampling that it writes. Every encode writes 4:2:0, and an H.264 encode is always 8-bit SDR,
   * because almost no device decodes 10-bit H.264 (#397). {@code masteringDisplay} and {@code
   * contentLightLevel} are the HDR10 static metadata that a PQ encode writes, and only a PQ encode
   * holds them.
   */
  @Builder
  record Encode(
      @NonNull Optional<EncodeCodecProfile> codecProfile,
      @NonNull VideoDynamicRange dynamicRange,
      int bitDepth,
      @NonNull ChromaSubsampling chromaSubsampling,
      @NonNull Optional<MasteringDisplayColorVolume> masteringDisplay,
      @NonNull Optional<ContentLightLevel> contentLightLevel)
      implements PlannedVideoOutput {

    // Decision 173 of #397: a P3 D65 display of 1000 and 0.0001 cd/m², in HEVC SEI units, and a
    // content light level of 0, which CTA-861.3 reads as unknown. Never invented source values.
    private static final MasteringDisplayColorVolume FALLBACK_DISPLAY =
        MasteringDisplayColorVolume.builder()
            .red(new ChromaticityCoordinates(34_000, 16_000))
            .green(new ChromaticityCoordinates(13_250, 34_500))
            .blue(new ChromaticityCoordinates(7_500, 3_000))
            .whitePoint(new ChromaticityCoordinates(15_635, 16_450))
            .maxLuminance(10_000_000)
            .minLuminance(1)
            .build();
    private static final ContentLightLevel UNKNOWN_LIGHT_LEVEL = new ContentLightLevel(0, 0);

    public Encode {
      requireSpecified(dynamicRange);
      if (bitDepth != 8 && bitDepth != 10) {
        throw new IllegalArgumentException("A planned encode's bit depth must be 8 or 10");
      }

      if (chromaSubsampling != ChromaSubsampling.CHROMA_420) {
        throw new IllegalArgumentException("A planned encode must write 4:2:0");
      }

      if (codecProfile.filter(EncodeCodecProfile.H264_HIGH::equals).isPresent()
          && (bitDepth != 8 || dynamicRange.compatibleBase() != CompatibleBase.SDR)) {
        throw new IllegalArgumentException("A planned H.264 encode must be 8-bit SDR");
      }

      if (dynamicRange.compatibleBase() != CompatibleBase.PQ
          && (masteringDisplay.isPresent() || contentLightLevel.isPresent())) {
        throw new IllegalArgumentException("Only a planned PQ encode holds static HDR metadata");
      }
    }

    /** Every encode is 8-bit 4:2:0 SDR unless the decision names another format (#397). */
    public static Encode sdr(@NonNull Optional<EncodeCodecProfile> codecProfile) {
      return Encode.builder()
          .codecProfile(codecProfile)
          .dynamicRange(VideoDynamicRange.SDR)
          .bitDepth(8)
          .chromaSubsampling(ChromaSubsampling.CHROMA_420)
          .build();
    }

    /**
     * The 4:2:0 encode in the format. A PQ encode takes each static HDR value that the probe stored
     * for the source, and otherwise the fallback display and an unknown content light level, so
     * every job attempt of a variant writes the same metadata (#397).
     */
    public static Encode of(
        @NonNull Optional<EncodeCodecProfile> codecProfile,
        @NonNull EncodeFormat format,
        @NonNull Optional<VideoPlaybackFacts> source) {
      var encode =
          Encode.builder()
              .codecProfile(codecProfile)
              .dynamicRange(
                  new VideoDynamicRange(
                      format.compatibleBase(), Presence.ABSENT, new DolbyVision.Absent()))
              .bitDepth(format.bitDepth())
              .chromaSubsampling(ChromaSubsampling.CHROMA_420);
      if (format.range() == VideoRange.PQ) {
        encode
            .masteringDisplay(
                Optional.of(
                    source.flatMap(VideoPlaybackFacts::masteringDisplay).orElse(FALLBACK_DISPLAY)))
            .contentLightLevel(
                Optional.of(
                    source
                        .flatMap(VideoPlaybackFacts::contentLightLevel)
                        .orElse(UNKNOWN_LIGHT_LEVEL)));
      }

      return encode.build();
    }

    // Lombok builder defaults — its generated setters assign them
    @SuppressWarnings({"java:S1068", "java:S9438"})
    public static class EncodeBuilder {
      private Optional<MasteringDisplayColorVolume> masteringDisplay = Optional.empty();
      private Optional<ContentLightLevel> contentLightLevel = Optional.empty();
    }
  }
}
