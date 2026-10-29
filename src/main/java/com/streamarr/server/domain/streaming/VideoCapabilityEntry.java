package com.streamarr.server.domain.streaming;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * One video capability entry (ADR 0049): one group of values and maximums that the player accepts
 * together for one codec family. The player accepts every combination that takes one value from
 * each list and stays within every maximum. {@code compatibleBases} names only SDR, PQ or HLG.
 * {@code hls} tells whether the entry applies to HLS delivery.
 */
@Builder
public record VideoCapabilityEntry(
    @NonNull ProbedCodecFamily codecFamily,
    @NonNull List<VideoCodecProfileCapability> codecProfiles,
    int maxBitDepth,
    int maxWidth,
    int maxHeight,
    double maxFrameRate,
    @NonNull List<CompatibleBase> compatibleBases,
    @NonNull List<DolbyVisionProfileCapability> dolbyVisionProfiles,
    boolean hdr10Plus,
    boolean hls) {

  private static final EnumSet<CompatibleBase> DECLARABLE_COMPATIBLE_BASES =
      EnumSet.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);

  public VideoCapabilityEntry {
    if (codecFamily == ProbedCodecFamily.OTHER) {
      throw new IllegalArgumentException("A video capability entry names a known codec family");
    }

    if (!DECLARABLE_COMPATIBLE_BASES.containsAll(compatibleBases)) {
      throw new IllegalArgumentException(
          "A video capability entry declares only SDR, PQ or HLG compatible bases");
    }

    codecProfiles = List.copyOf(codecProfiles);
    compatibleBases = List.copyOf(compatibleBases);
    dolbyVisionProfiles = List.copyOf(dolbyVisionProfiles);
  }

  /**
   * Whether this entry accepts every property of the stream copy over HLS (ADR 0049). A copy that a
   * probe version 4 outcome describes keeps that version's decision until the file's probe version
   * 5 outcome is stored (ADR 0032): it needs only an HLS entry of its codec family.
   */
  public boolean acceptsHlsCopy(@NonNull VideoStreamCopy copy) {
    if (!hls || copy.codecFamily() != codecFamily) {
      return false;
    }

    return copy.videoProperties()
        .map(properties -> acceptsEveryProperty(copy, properties))
        .orElse(true);
  }

  // An unknown codec level or tier fits wherever the known picture size and frame rate fit, so it
  // passes its own check and leaves the decision to those maximums.
  private boolean acceptsEveryProperty(VideoStreamCopy copy, VideoStreamProperties properties) {
    return acceptsCodecProfile(properties)
        && acceptsBitDepth(copy.bitDepth())
        && acceptsPictureSize(copy.decodedSize())
        && acceptsFrameRate(copy.frameRate());
  }

  private boolean acceptsCodecProfile(VideoStreamProperties properties) {
    if (properties.codecProfile().isEmpty()) {
      return false;
    }

    var profile = properties.codecProfile().get();
    return codecProfiles.stream()
        .filter(capability -> capability.profile() == profile)
        .anyMatch(
            capability ->
                capability.acceptsLevelAndTier(properties.codecLevel(), properties.codecTier()));
  }

  private boolean acceptsBitDepth(OptionalInt bitDepth) {
    return bitDepth.isPresent() && bitDepth.getAsInt() <= maxBitDepth;
  }

  private boolean acceptsPictureSize(Optional<PictureSize> size) {
    return size.filter(known -> known.width() <= maxWidth && known.height() <= maxHeight)
        .isPresent();
  }

  // A stream without a usable frame rate cannot be encoded (#446), so a copy is its only path.
  private boolean acceptsFrameRate(Optional<Fraction> frameRate) {
    return frameRate.map(rate -> rate.toDouble() <= maxFrameRate).orElse(true);
  }
}
