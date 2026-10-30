package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
  // A frame rate fits up to half a percent above the maximum. Timestamp rounding can put a measured
  // rate slightly above the source's standard rate, such as 60.2 fps for a 60 fps stream with
  // millisecond timestamps. Decimal arithmetic keeps a rate exactly at the margin inside it:
  // 50 × 1.005 in binary floating point is 50.24999999999999.
  private static final BigDecimal FRAME_RATE_MARGIN = new BigDecimal("1.005");
  // Every encode is 8-bit SDR video without Dolby Vision or HDR10+ information.
  private static final int ENCODED_BIT_DEPTH = 8;
  // ADR 0037's frame-rate rule chooses no output frame rate below 10 fps.
  private static final BigDecimal LOWEST_ENCODED_FRAME_RATE = BigDecimal.TEN;

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

  /**
   * The largest encoded output within the request's bounds that this entry accepts over HLS (ADR
   * 0049), or empty when this entry accepts no encode of the request. The output keeps the source's
   * aspect ratio, and the entry's maximums bound it in the source's orientation. A frame rate above
   * the maximum is divided by the smallest whole number that fits, never below 10 fps. A picture
   * whose codec level is above the profile's maximum level steps down in size until the level fits,
   * and a bitrate above what that level allows is capped at it.
   */
  public Optional<EncodedVideoOutput> fitHlsEncode(@NonNull EncodedVariantRequest request) {
    if (!acceptsHlsEncode(request.codecProfile())) {
      return Optional.empty();
    }

    var frameRate = encodedFrameRate(request.frameRate());
    if (frameRate.isEmpty()) {
      return Optional.empty();
    }

    return codecProfiles.stream()
        .filter(capability -> capability.profile() == request.codecProfile().codecProfile())
        .map(capability -> fitWithinLevel(capability, request, frameRate.get()))
        .flatMap(Optional::stream)
        .max(EncodedVideoOutput.FASTER_FRAME_RATE_THEN_LARGER_PICTURE_THEN_HIGHER_BITRATE);
  }

  private boolean acceptsHlsEncode(EncodeCodecProfile codecProfile) {
    return hls
        && codecProfile.codecProfile().codecFamily() == codecFamily
        && compatibleBases.contains(CompatibleBase.SDR)
        && ENCODED_BIT_DEPTH <= maxBitDepth;
  }

  // The margin also lets a declared 29.97 or 59.94 accept the exact NTSC rate 30000/1001 or
  // 60000/1001, which lies just above it. Dividing by a whole number keeps motion even.
  private Optional<Fraction> encodedFrameRate(Fraction rate) {
    var fastestAcceptedRate = BigDecimal.valueOf(maxFrameRate).multiply(FRAME_RATE_MARGIN);
    var divisor =
        BigDecimal.valueOf(rate.numerator())
            .divide(
                BigDecimal.valueOf(rate.denominator()).multiply(fastestAcceptedRate),
                0,
                RoundingMode.CEILING)
            .max(BigDecimal.ONE);
    if (isBelowLowestEncodedFrameRate(rate, divisor)) {
      return Optional.empty();
    }

    return Optional.of(rate.dividedBy(divisor.intValueExact()));
  }

  // Every segment interval needs a frame to start it, and the segment list stays the same however
  // slow a variant runs (ADR 0037). Dividing never goes below the frame-rate rule's lowest rate, so
  // the divided denominator also stays within an int. The floor takes the same margin as the
  // maximum, so an exact NTSC 30000/1001 divided by 3 still counts as 10 fps.
  private static boolean isBelowLowestEncodedFrameRate(Fraction rate, BigDecimal divisor) {
    var lowestNumerator =
        LOWEST_ENCODED_FRAME_RATE
            .multiply(BigDecimal.valueOf(rate.denominator()))
            .multiply(divisor);
    return BigDecimal.valueOf(rate.numerator())
            .multiply(FRAME_RATE_MARGIN)
            .compareTo(lowestNumerator)
        < 0;
  }

  private Optional<EncodedVideoOutput> fitWithinLevel(
      VideoCodecProfileCapability capability, EncodedVariantRequest request, Fraction frameRate) {
    var codecProfile = request.codecProfile();
    var highestBitrate = codecProfile.highestBitrateAtOrBelow(capability.maxLevel());
    if (highestBitrate.isEmpty()) {
      return Optional.empty();
    }

    var bitrate = Math.min(request.bitrate(), highestBitrate.getAsLong());
    var source = request.source();
    var entryBox = maximumsOrientedLike(source);
    var bounds =
        new PictureSize(
            Math.min(request.bounds().width(), entryBox.width()),
            Math.min(request.bounds().height(), entryBox.height()));
    return EncodedPictureSizes.picturesWithin(source, bounds)
        .map(
            size ->
                EncodedVideoOutput.builder()
                    .codecFamily(codecProfile.codecFamilyName())
                    .width(size.width())
                    .height(size.height())
                    .frameRate(frameRate)
                    .bitrate(bitrate)
                    .build())
        .filter(output -> acceptsLevelAndTier(capability, output))
        .findFirst();
  }

  // The maximums bound a picture in either orientation, so a portrait source is encoded within the
  // entry's maximums turned to match it.
  private PictureSize maximumsOrientedLike(PictureSize source) {
    var larger = Math.max(maxWidth, maxHeight);
    var smaller = Math.min(maxWidth, maxHeight);
    if (source.height() > source.width()) {
      return new PictureSize(smaller, larger);
    }

    return new PictureSize(larger, smaller);
  }

  private static boolean acceptsLevelAndTier(
      VideoCodecProfileCapability capability, EncodedVideoOutput output) {
    return VideoEncodeDecision.allowing(output)
        .filter(
            decision ->
                capability.acceptsLevelAndTier(OptionalInt.of(decision.level()), decision.tier()))
        .isPresent();
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

    var fastestAcceptedRate = BigDecimal.valueOf(maxFrameRate).multiply(FRAME_RATE_MARGIN);
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
      case CopiedDolbyVision.Undeclarable _, CopiedDolbyVision.NeedsDolbyVisionSampleEntry _ ->
          false;
    };
  }

  // A base of NONE comes only with Dolby Vision, which decides alone whether the copy fits.
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
