package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

// Maps the facts that probe version 5 adds to each video stream and checks them against the
// version 5 contract. Domain and protobuf contracts intentionally share simple names.
final class RemoteStreamPropertiesMapper {

  private static final int DOLBY_VISION_PROFILE_5 = 5;
  private static final int DOLBY_VISION_PROFILE_10 = 10;
  private static final Set<String> RECORD_LESS_BASELESS_CODECS = Set.of("hevc", "av1");
  // vdr_bit_depth_minus8 + 8, and a uint32 above Integer.MAX_VALUE arrives as a negative int.
  private static final int MINIMUM_VDR_BIT_DEPTH = 8;

  private RemoteStreamPropertiesMapper() {}

  static StreamInfo.StreamInfoBuilder addStreamProperties(
      StreamInfo.StreamInfoBuilder builder, ProbeStreamInfo stream) {
    if (!"video".equals(stream.getCodecType())) {
      return builder;
    }

    checkStaticHdrMetadataOmission(stream);
    checkFrameEvidence(stream);
    checkCodecVisibility(stream);
    checkBaselessProfileCompatibleBase(stream);
    return builder.videoProperties(Optional.of(properties(stream)));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static DolbyVisionRpuHeader rpuHeader(com.streamarr.transcode.v1.DolbyVisionRpuHeader header) {
    if (header.getVdrRpuProfile() < 0) {
      throw contractViolation("Worker probe RPU profile exceeds the supported integer range");
    }

    if (header.getVdrBitDepth() < MINIMUM_VDR_BIT_DEPTH) {
      throw contractViolation("Worker probe RPU bit depth lies outside its encoded range");
    }

    return DolbyVisionRpuHeader.builder()
        .vdrRpuProfile(header.getVdrRpuProfile())
        .baseLayerFullRange(header.getBaseLayerFullRange())
        .enhancementLayerSpatialResamplingFilter(
            header.getEnhancementLayerSpatialResamplingFilter())
        .disableResidual(header.getDisableResidual())
        .vdrBitDepth(header.getVdrBitDepth())
        .build();
  }

  private static void checkStaticHdrMetadataOmission(ProbeStreamInfo stream) {
    if (stream.getMasteringDisplayOmitted() && stream.hasMasteringDisplay()) {
      throw contractViolation("Worker probe omitted a mastering display and still listed it");
    }

    if (stream.getContentLightLevelOmitted() && stream.hasContentLightLevel()) {
      throw contractViolation("Worker probe omitted a content light level and still listed it");
    }
  }

  private static void checkFrameEvidence(ProbeStreamInfo stream) {
    var range = stream.getDynamicRange();
    // A sample layout needs an observed RPU, which the next check enforces, so it needs frames too.
    var reportsFrameFeature =
        range.getHdr10Plus() != Presence.PRESENCE_UNSPECIFIED
            || range.hasDolbyVisionAbsent()
            || range.getDolbyVisionPresent().getObservedRpu() != Presence.PRESENCE_UNSPECIFIED;
    if (reportsFrameFeature && !stream.getFirstFramesRead()) {
      throw contractViolation("Worker probe reported a frame feature without reading frames");
    }

    var present = range.getDolbyVisionPresent();
    if (present.getObservedRpu() == Presence.PRESENCE_PRESENT) {
      return;
    }

    if (present.hasFirstRpuHeader()
        || present.getRpuStartsSample() != Presence.PRESENCE_UNSPECIFIED) {
      throw contractViolation("Worker probe described RPUs that it did not observe");
    }
  }

  private static void checkCodecVisibility(ProbeStreamInfo stream) {
    switch (stream.getCodec()) {
      case "h264", "hevc" -> {
        // The worker reads every frame feature of these codecs from the frames or the packets.
      }
      case "av1" -> checkAv1Visibility(stream.getDynamicRange());
      default -> checkInvisibleFrameFeatures(stream.getDynamicRange());
    }
  }

  // FFmpeg's AV1 decoders drop an RPU that they cannot parse, and a whole frame whose HDR10+
  // message they cannot parse. The worker parses no AV1 OBU, so it reads no AV1 sample layout.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static void checkAv1Visibility(com.streamarr.transcode.v1.VideoDynamicRange range) {
    var present = range.getDolbyVisionPresent();
    if (range.getHdr10Plus() == Presence.PRESENCE_ABSENT
        || range.hasDolbyVisionAbsent()
        || present.getObservedRpu() == Presence.PRESENCE_ABSENT
        || present.getRpuStartsSample() != Presence.PRESENCE_UNSPECIFIED) {
      throw contractViolation("Worker probe reported an AV1 fact that it cannot see");
    }
  }

  // The worker reads no frame feature of any other codec, which keeps the facts of probe version 4.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static void checkInvisibleFrameFeatures(
      com.streamarr.transcode.v1.VideoDynamicRange range) {
    if (range.getHdr10Plus() != Presence.PRESENCE_UNSPECIFIED
        || range.hasDolbyVisionAbsent()
        || range.getDolbyVisionPresent().getObservedRpu() != Presence.PRESENCE_UNSPECIFIED) {
      throw contractViolation("Worker probe reported a frame feature that it cannot see");
    }
  }

  // From probe version 5 on, profiles 5 and 10.0 have no compatible base whatever the signaled
  // transfer says.
  private static void checkBaselessProfileCompatibleBase(ProbeStreamInfo stream) {
    var range = stream.getDynamicRange();
    if (!range.hasDolbyVisionPresent()
        || !isBaselessProfile(stream, range.getDolbyVisionPresent())) {
      return;
    }

    if (range.getCompatibleBase() != CompatibleBase.COMPATIBLE_BASE_NONE) {
      throw contractViolation("Worker probe gave Dolby Vision profile 5 or 10.0 a compatible base");
    }
  }

  private static boolean isBaselessProfile(ProbeStreamInfo stream, DolbyVisionPresent present) {
    if (present.getDeclared().hasProfile()) {
      return isBaselessRecord(present.getDeclared());
    }

    // The header tells the profile when no record names one: the same header means profile 5 in
    // HEVC and profile 10.0 in AV1.
    var header = present.getFirstRpuHeader();
    return RECORD_LESS_BASELESS_CODECS.contains(stream.getCodec())
        && present.hasFirstRpuHeader()
        && header.getVdrRpuProfile() == 0
        && header.getBaseLayerFullRange();
  }

  private static boolean isBaselessRecord(DolbyVisionConfiguration configuration) {
    return switch (configuration.getProfile()) {
      case DOLBY_VISION_PROFILE_5 -> true;
      case DOLBY_VISION_PROFILE_10 ->
          configuration.hasBaseLayerCompatibilityId()
              && configuration.getBaseLayerCompatibilityId() == 0;
      default -> false;
    };
  }

  private static VideoStreamProperties properties(ProbeStreamInfo stream) {
    var profile = codecProfile(stream.getCodecProfile());
    var tier = codecTier(stream.getCodecTier());
    if (profile.isEmpty() && (stream.hasCodecLevel() || tier.isPresent())) {
      throw contractViolation("Worker probe reported a codec level or tier without a profile");
    }

    // The worker reads the profile from the record or sequence header of the stream's own codec.
    if (profile.isPresent() && !codecOf(profile.get()).equals(stream.getCodec())) {
      throw contractViolation("Worker probe gave a stream a codec profile of another codec");
    }

    if (tier.isPresent() && "h264".equals(stream.getCodec())) {
      throw contractViolation("Worker probe gave an H.264 codec profile a tier");
    }

    return VideoStreamProperties.builder()
        .codecProfile(profile)
        .codecLevel(codecLevel(stream))
        .codecTier(tier)
        .colorPrimaries(colorPrimaries(stream.getColorPrimaries()))
        .transferCharacteristics(transferCharacteristics(stream.getTransferCharacteristics()))
        .matrixCoefficients(matrixCoefficients(stream.getMatrixCoefficients()))
        .colorRange(colorRange(stream.getColorRange()))
        .firstFramesRead(stream.getFirstFramesRead())
        .masteringDisplayOmitted(stream.getMasteringDisplayOmitted())
        .contentLightLevelOmitted(stream.getContentLightLevelOmitted())
        .build();
  }

  private static OptionalInt codecLevel(ProbeStreamInfo stream) {
    if (!stream.hasCodecLevel()) {
      return OptionalInt.empty();
    }

    if (stream.getCodecLevel() <= 0) {
      throw contractViolation("Worker probe codec level is not a positive integer");
    }

    return OptionalInt.of(stream.getCodecLevel());
  }

  // The codec as ffprobe names it.
  private static String codecOf(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_CONSTRAINED_BASELINE,
          H264_BASELINE,
          H264_MAIN,
          H264_EXTENDED,
          H264_HIGH,
          H264_HIGH_10,
          H264_HIGH_422,
          H264_HIGH_444_PREDICTIVE ->
          "h264";
      case HEVC_MAIN, HEVC_MAIN_10, HEVC_MAIN_STILL_PICTURE, HEVC_FORMAT_RANGE_EXTENSIONS -> "hevc";
      case AV1_MAIN, AV1_HIGH, AV1_PROFESSIONAL -> "av1";
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<VideoCodecProfile> codecProfile(
      com.streamarr.transcode.v1.VideoCodecProfile profile) {
    return Optional.ofNullable(
        switch (profile) {
          case VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE ->
              VideoCodecProfile.H264_CONSTRAINED_BASELINE;
          case VIDEO_CODEC_PROFILE_H264_BASELINE -> VideoCodecProfile.H264_BASELINE;
          case VIDEO_CODEC_PROFILE_H264_MAIN -> VideoCodecProfile.H264_MAIN;
          case VIDEO_CODEC_PROFILE_H264_EXTENDED -> VideoCodecProfile.H264_EXTENDED;
          case VIDEO_CODEC_PROFILE_H264_HIGH -> VideoCodecProfile.H264_HIGH;
          case VIDEO_CODEC_PROFILE_H264_HIGH_10 -> VideoCodecProfile.H264_HIGH_10;
          case VIDEO_CODEC_PROFILE_H264_HIGH_422 -> VideoCodecProfile.H264_HIGH_422;
          case VIDEO_CODEC_PROFILE_H264_HIGH_444_PREDICTIVE ->
              VideoCodecProfile.H264_HIGH_444_PREDICTIVE;
          case VIDEO_CODEC_PROFILE_HEVC_MAIN -> VideoCodecProfile.HEVC_MAIN;
          case VIDEO_CODEC_PROFILE_HEVC_MAIN_10 -> VideoCodecProfile.HEVC_MAIN_10;
          case VIDEO_CODEC_PROFILE_HEVC_MAIN_STILL_PICTURE ->
              VideoCodecProfile.HEVC_MAIN_STILL_PICTURE;
          case VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS ->
              VideoCodecProfile.HEVC_FORMAT_RANGE_EXTENSIONS;
          case VIDEO_CODEC_PROFILE_AV1_MAIN -> VideoCodecProfile.AV1_MAIN;
          case VIDEO_CODEC_PROFILE_AV1_HIGH -> VideoCodecProfile.AV1_HIGH;
          case VIDEO_CODEC_PROFILE_AV1_PROFESSIONAL -> VideoCodecProfile.AV1_PROFESSIONAL;
          case VIDEO_CODEC_PROFILE_UNSPECIFIED, UNRECOGNIZED -> null;
        });
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<VideoCodecTier> codecTier(
      com.streamarr.transcode.v1.VideoCodecTier tier) {
    return switch (tier) {
      case VIDEO_CODEC_TIER_MAIN -> Optional.of(VideoCodecTier.MAIN);
      case VIDEO_CODEC_TIER_HIGH -> Optional.of(VideoCodecTier.HIGH);
      case VIDEO_CODEC_TIER_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<ColorPrimaries> colorPrimaries(
      com.streamarr.transcode.v1.ColorPrimaries primaries) {
    return Optional.ofNullable(
        switch (primaries) {
          case COLOR_PRIMARIES_BT709 -> ColorPrimaries.BT709;
          case COLOR_PRIMARIES_BT470M -> ColorPrimaries.BT470M;
          case COLOR_PRIMARIES_BT470BG -> ColorPrimaries.BT470BG;
          case COLOR_PRIMARIES_SMPTE170M -> ColorPrimaries.SMPTE170M;
          case COLOR_PRIMARIES_SMPTE240M -> ColorPrimaries.SMPTE240M;
          case COLOR_PRIMARIES_FILM -> ColorPrimaries.FILM;
          case COLOR_PRIMARIES_BT2020 -> ColorPrimaries.BT2020;
          case COLOR_PRIMARIES_SMPTE428 -> ColorPrimaries.SMPTE428;
          case COLOR_PRIMARIES_SMPTE431 -> ColorPrimaries.SMPTE431;
          case COLOR_PRIMARIES_SMPTE432 -> ColorPrimaries.SMPTE432;
          case COLOR_PRIMARIES_EBU3213 -> ColorPrimaries.EBU3213;
          case COLOR_PRIMARIES_UNSPECIFIED, UNRECOGNIZED -> null;
        });
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<TransferCharacteristics> transferCharacteristics(
      com.streamarr.transcode.v1.TransferCharacteristics transfer) {
    return Optional.ofNullable(
        switch (transfer) {
          case TRANSFER_CHARACTERISTICS_BT709 -> TransferCharacteristics.BT709;
          case TRANSFER_CHARACTERISTICS_BT470M -> TransferCharacteristics.BT470M;
          case TRANSFER_CHARACTERISTICS_BT470BG -> TransferCharacteristics.BT470BG;
          case TRANSFER_CHARACTERISTICS_SMPTE170M -> TransferCharacteristics.SMPTE170M;
          case TRANSFER_CHARACTERISTICS_SMPTE240M -> TransferCharacteristics.SMPTE240M;
          case TRANSFER_CHARACTERISTICS_LINEAR -> TransferCharacteristics.LINEAR;
          case TRANSFER_CHARACTERISTICS_LOG100 -> TransferCharacteristics.LOG100;
          case TRANSFER_CHARACTERISTICS_LOG316 -> TransferCharacteristics.LOG316;
          case TRANSFER_CHARACTERISTICS_IEC61966_2_4 -> TransferCharacteristics.IEC61966_2_4;
          case TRANSFER_CHARACTERISTICS_BT1361 -> TransferCharacteristics.BT1361;
          case TRANSFER_CHARACTERISTICS_IEC61966_2_1 -> TransferCharacteristics.IEC61966_2_1;
          case TRANSFER_CHARACTERISTICS_BT2020_10 -> TransferCharacteristics.BT2020_10;
          case TRANSFER_CHARACTERISTICS_BT2020_12 -> TransferCharacteristics.BT2020_12;
          case TRANSFER_CHARACTERISTICS_SMPTE2084 -> TransferCharacteristics.SMPTE2084;
          case TRANSFER_CHARACTERISTICS_SMPTE428 -> TransferCharacteristics.SMPTE428;
          case TRANSFER_CHARACTERISTICS_ARIB_STD_B67 -> TransferCharacteristics.ARIB_STD_B67;
          case TRANSFER_CHARACTERISTICS_UNSPECIFIED, UNRECOGNIZED -> null;
        });
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<MatrixCoefficients> matrixCoefficients(
      com.streamarr.transcode.v1.MatrixCoefficients matrix) {
    return Optional.ofNullable(
        switch (matrix) {
          case MATRIX_COEFFICIENTS_IDENTITY -> MatrixCoefficients.IDENTITY;
          case MATRIX_COEFFICIENTS_BT709 -> MatrixCoefficients.BT709;
          case MATRIX_COEFFICIENTS_FCC -> MatrixCoefficients.FCC;
          case MATRIX_COEFFICIENTS_BT470BG -> MatrixCoefficients.BT470BG;
          case MATRIX_COEFFICIENTS_SMPTE170M -> MatrixCoefficients.SMPTE170M;
          case MATRIX_COEFFICIENTS_SMPTE240M -> MatrixCoefficients.SMPTE240M;
          case MATRIX_COEFFICIENTS_YCGCO -> MatrixCoefficients.YCGCO;
          case MATRIX_COEFFICIENTS_BT2020_NCL -> MatrixCoefficients.BT2020_NCL;
          case MATRIX_COEFFICIENTS_BT2020_CL -> MatrixCoefficients.BT2020_CL;
          case MATRIX_COEFFICIENTS_SMPTE2085 -> MatrixCoefficients.SMPTE2085;
          case MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL -> MatrixCoefficients.CHROMA_DERIVED_NCL;
          case MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL -> MatrixCoefficients.CHROMA_DERIVED_CL;
          case MATRIX_COEFFICIENTS_ICTCP -> MatrixCoefficients.ICTCP;
          case MATRIX_COEFFICIENTS_UNSPECIFIED, UNRECOGNIZED -> null;
        });
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<ColorRange> colorRange(com.streamarr.transcode.v1.ColorRange range) {
    return switch (range) {
      case COLOR_RANGE_LIMITED -> Optional.of(ColorRange.LIMITED);
      case COLOR_RANGE_FULL -> Optional.of(ColorRange.FULL);
      case COLOR_RANGE_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  private static ProbeContractViolationException contractViolation(String message) {
    return new ProbeContractViolationException(message);
  }
}
