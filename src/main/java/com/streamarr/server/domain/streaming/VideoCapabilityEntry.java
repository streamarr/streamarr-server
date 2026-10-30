package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
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
  // Timestamp rounding can put a measured rate slightly above the source's standard rate, such as
  // 60.2 fps for a 60 fps stream with millisecond timestamps.
  // Decimal arithmetic keeps a rate exactly at the margin inside it: 50 × 1.005 in binary floating
  // point is 50.24999999999999.
  private static final BigDecimal MEASURED_FRAME_RATE_MARGIN = new BigDecimal("1.005");

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
        && acceptsFrameRate(copy.frameRate())
        && acceptsDynamicRange(copy);
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

  // The maximums bound a picture in either orientation, so a portrait phone video fits an entry
  // declared as landscape. The codec level still bounds the picture's total size.
  private boolean acceptsPictureSize(Optional<PictureSize> size) {
    return size.filter(this::fitsInEitherOrientation).isPresent();
  }

  private boolean fitsInEitherOrientation(PictureSize size) {
    var longerSide = Math.max(size.width(), size.height());
    var shorterSide = Math.min(size.width(), size.height());
    return longerSide <= Math.max(maxWidth, maxHeight)
        && shorterSide <= Math.min(maxWidth, maxHeight);
  }

  // A stream without a usable frame rate cannot be encoded (#446), so a copy is its only path.
  private boolean acceptsFrameRate(OptionalDouble frameRate) {
    if (frameRate.isEmpty()) {
      return true;
    }

    var fastestAcceptedRate = BigDecimal.valueOf(maxFrameRate).multiply(MEASURED_FRAME_RATE_MARGIN);
    return BigDecimal.valueOf(frameRate.getAsDouble()).compareTo(fastestAcceptedRate) <= 0;
  }

  // Declaring a compatible base declares neither Dolby Vision nor HDR10+ (ADR 0049).
  private boolean acceptsDynamicRange(VideoStreamCopy copy) {
    var range = copy.dynamicRange();
    return acceptsDolbyVision(copy)
        && acceptsCompatibleBase(range.compatibleBase())
        && acceptsHdr10Plus(range.hdr10Plus());
  }

  private boolean acceptsDolbyVision(VideoStreamCopy copy) {
    return switch (CopiedDolbyVision.of(copy.codecFamily(), copy.dynamicRange())) {
      case CopiedDolbyVision.None _, CopiedDolbyVision.KeptUnderSdrBase _ -> true;
      case CopiedDolbyVision.Declarable(var profile, var level) -> listsDolbyVision(profile, level);
      case CopiedDolbyVision.Undeclarable _ -> false;
    };
  }

  // A base of NONE comes only with Dolby Vision, whose profile and level alone admit it.
  private boolean acceptsCompatibleBase(CompatibleBase base) {
    return switch (base) {
      case UNSPECIFIED -> compatibleBases.contains(CompatibleBase.SDR);
      case NONE -> true;
      case SDR, PQ, HLG -> compatibleBases.contains(base);
    };
  }

  private boolean acceptsHdr10Plus(Presence hdr10PlusInformation) {
    return hdr10PlusInformation != Presence.PRESENT || hdr10Plus;
  }

  private boolean listsDolbyVision(DolbyVisionProfile profile, int level) {
    return dolbyVisionProfiles.stream()
        .anyMatch(capability -> capability.profile() == profile && level <= capability.maxLevel());
  }
}
