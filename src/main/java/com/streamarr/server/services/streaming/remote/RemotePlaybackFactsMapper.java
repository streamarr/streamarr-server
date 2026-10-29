package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.DurationOrBuilder;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.FrameRatePacketScan;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.MatroskaStatistics;
import com.streamarr.server.domain.streaming.OutputFrameRateBasis;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.FrameRateRuleResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;

// Maps the facts that probe version 4 adds for playback, and the RPU facts of probe version 5.
// Domain and protobuf contracts intentionally share simple names.
final class RemotePlaybackFactsMapper {

  private static final String WORKER_PROBE_PREFIX = "Worker probe ";
  private static final int MAXIMUM_ROTATION_DEGREES = 180;
  private static final int MAXIMUM_CHROMATICITY_COORDINATE = 50000;
  private static final int MAXIMUM_CONTENT_LIGHT_LEVEL = 65535;
  private static final int MAXIMUM_STREAM_TEXT_BYTES = 512;

  private final Function<DurationOrBuilder, Duration> durations;

  RemotePlaybackFactsMapper(Function<DurationOrBuilder, Duration> durations) {
    this.durations = durations;
  }

  // A probe version that reads no frames reports no RPU header and no RPU placement.
  StreamInfo.StreamInfoBuilder addStreamFacts(
      StreamInfo.StreamInfoBuilder builder, ProbeStreamInfo stream, boolean readsFrames) {
    return builder
        .dynamicRange(
            stream.hasDynamicRange()
                ? Optional.of(dynamicRange(stream.getDynamicRange(), readsFrames))
                : Optional.empty())
        .bitDepth(positive(stream.hasBitDepth(), stream.getBitDepth(), "bit depth"))
        .chromaSubsampling(chromaSubsampling(stream.getChromaSubsampling()))
        .rotationDegrees(rotation(stream))
        .containerCropping(
            stream.hasContainerCropping()
                ? Optional.of(cropping(stream.getContainerCropping()))
                : Optional.empty())
        .codecString(codecString(stream))
        .sampleRate(positive(stream.hasSampleRateHz(), stream.getSampleRateHz(), "sample rate"))
        .audioProfile(
            stream.hasAudioProfile() ? Optional.of(stream.getAudioProfile()) : Optional.empty())
        .endTime(
            stream.hasEndTime()
                ? Optional.of(durations.apply(stream.getEndTime()))
                : Optional.empty())
        .masteringDisplay(
            stream.hasMasteringDisplay()
                ? Optional.of(masteringDisplay(stream.getMasteringDisplay()))
                : Optional.empty())
        .contentLightLevel(
            stream.hasContentLightLevel()
                ? Optional.of(contentLightLevel(stream.getContentLightLevel()))
                : Optional.empty())
        .title(streamText(stream.hasTitle(), stream.getTitle()))
        .handlerName(streamText(stream.hasHandlerName(), stream.getHandlerName()))
        .hearingImpaired(
            stream.hasIsHearingImpaired()
                ? Optional.of(stream.getIsHearingImpaired())
                : Optional.empty());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static MasteringDisplayColorVolume masteringDisplay(
      com.streamarr.transcode.v1.MasteringDisplayColorVolume display) {
    if (!display.hasRed()
        || !display.hasGreen()
        || !display.hasBlue()
        || !display.hasWhitePoint()) {
      throw contractViolation("Worker probe mastering display lacks chromaticity coordinates");
    }

    return MasteringDisplayColorVolume.builder()
        .red(coordinates(display.getRed()))
        .green(coordinates(display.getGreen()))
        .blue(coordinates(display.getBlue()))
        .whitePoint(coordinates(display.getWhitePoint()))
        .maxLuminance(Integer.toUnsignedLong(display.getMaxLuminance()))
        .minLuminance(Integer.toUnsignedLong(display.getMinLuminance()))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ChromaticityCoordinates coordinates(
      com.streamarr.transcode.v1.ChromaticityCoordinates coordinates) {
    return new ChromaticityCoordinates(
        boundedUnsigned(
            coordinates.getX(), MAXIMUM_CHROMATICITY_COORDINATE, "chromaticity coordinate"),
        boundedUnsigned(
            coordinates.getY(), MAXIMUM_CHROMATICITY_COORDINATE, "chromaticity coordinate"));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ContentLightLevel contentLightLevel(
      com.streamarr.transcode.v1.ContentLightLevel level) {
    return new ContentLightLevel(
        boundedUnsigned(
            level.getMaxContentLightLevel(), MAXIMUM_CONTENT_LIGHT_LEVEL, "content light level"),
        boundedUnsigned(
            level.getMaxFrameAverageLightLevel(),
            MAXIMUM_CONTENT_LIGHT_LEVEL,
            "content light level"));
  }

  private static int boundedUnsigned(int value, int maximum, String fact) {
    if (Integer.compareUnsigned(value, maximum) > 0) {
      throw contractViolation(WORKER_PROBE_PREFIX + fact + " exceeds its encoded range");
    }

    return value;
  }

  private static Optional<String> streamText(boolean present, String value) {
    if (!present) {
      return Optional.empty();
    }

    if (value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_STREAM_TEXT_BYTES) {
      throw contractViolation("Worker probe stream text exceeds the UTF-8 byte limit");
    }

    return Optional.of(value);
  }

  ChosenVideoStream chosenVideoStream(ProbeMediaInfo media, List<StreamInfo> streams) {
    if (!media.hasChosenVideoStreamIndex()) {
      throw contractViolation("Worker probe result names no chosen video stream");
    }

    var index = media.getChosenVideoStreamIndex();
    if (streams.stream()
        .noneMatch(stream -> stream.index() == index && "video".equals(stream.codecType()))) {
      throw contractViolation("Worker probe result chose a stream that is not a video stream");
    }

    if (!media.hasFrameRateRule()) {
      throw contractViolation("Worker probe result carries no frame-rate rule");
    }

    return new ChosenVideoStream(index, frameRateRule(media.getFrameRateRule()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static VideoDynamicRange dynamicRange(
      com.streamarr.transcode.v1.VideoDynamicRange range, boolean readsFrames) {
    return new VideoDynamicRange(
        compatibleBase(range.getCompatibleBase()),
        presence(range.getHdr10Plus()),
        dolbyVision(range, readsFrames));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static CompatibleBase compatibleBase(com.streamarr.transcode.v1.CompatibleBase base) {
    return switch (base) {
      case COMPATIBLE_BASE_NONE -> CompatibleBase.NONE;
      case COMPATIBLE_BASE_SDR -> CompatibleBase.SDR;
      case COMPATIBLE_BASE_PQ -> CompatibleBase.PQ;
      case COMPATIBLE_BASE_HLG -> CompatibleBase.HLG;
      case COMPATIBLE_BASE_UNSPECIFIED -> CompatibleBase.UNSPECIFIED;
      case UNRECOGNIZED ->
          throw contractViolation("Worker probe result carries an unrecognized compatible base");
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Presence presence(com.streamarr.transcode.v1.Presence presence) {
    return switch (presence) {
      case PRESENCE_ABSENT -> Presence.ABSENT;
      case PRESENCE_PRESENT -> Presence.PRESENT;
      case PRESENCE_UNSPECIFIED, UNRECOGNIZED -> Presence.UNSPECIFIED;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DolbyVision dolbyVision(
      com.streamarr.transcode.v1.VideoDynamicRange range, boolean readsFrames) {
    return switch (range.getDolbyVisionCase()) {
      case DOLBY_VISION_ABSENT -> new DolbyVision.Absent();
      case DOLBY_VISION_PRESENT -> dolbyVisionPresent(range.getDolbyVisionPresent(), readsFrames);
      case DOLBYVISION_NOT_SET -> new DolbyVision.Unspecified();
    };
  }

  private static DolbyVision.Present dolbyVisionPresent(
      DolbyVisionPresent present, boolean readsFrames) {
    var declared =
        present.hasDeclared()
            ? Optional.of(configuration(present.getDeclared()))
            : Optional.<DolbyVisionConfiguration>empty();
    var observedRpu = presence(present.getObservedRpu());
    if (!readsFrames) {
      return new DolbyVision.Present(declared, observedRpu);
    }

    return new DolbyVision.Present(
        declared,
        observedRpu,
        present.hasFirstRpuHeader()
            ? Optional.of(RemoteStreamPropertiesMapper.rpuHeader(present.getFirstRpuHeader()))
            : Optional.empty(),
        rpuPlacement(present.getRpuPlacement()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DolbyVisionRpuPlacement rpuPlacement(
      com.streamarr.transcode.v1.DolbyVisionRpuPlacement placement) {
    return switch (placement) {
      case DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE ->
          DolbyVisionRpuPlacement.AFTER_OWN_PICTURE;
      case DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE -> DolbyVisionRpuPlacement.STARTS_SAMPLE;
      case DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED, UNRECOGNIZED ->
          DolbyVisionRpuPlacement.UNSPECIFIED;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DolbyVisionConfiguration configuration(
      com.streamarr.transcode.v1.DolbyVisionConfiguration configuration) {
    return DolbyVisionConfiguration.builder()
        .profile(
            unsigned(
                configuration.hasProfile(), configuration.getProfile(), "Dolby Vision profile"))
        .level(unsigned(configuration.hasLevel(), configuration.getLevel(), "Dolby Vision level"))
        .baseLayerCompatibilityId(
            unsigned(
                configuration.hasBaseLayerCompatibilityId(),
                configuration.getBaseLayerCompatibilityId(),
                "Dolby Vision compatibility identifier"))
        .baseLayer(configuration.getBaseLayer())
        .enhancementLayer(configuration.getEnhancementLayer())
        .rpu(configuration.getRpu())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<ChromaSubsampling> chromaSubsampling(
      com.streamarr.transcode.v1.ChromaSubsampling subsampling) {
    return switch (subsampling) {
      case CHROMA_SUBSAMPLING_420 -> Optional.of(ChromaSubsampling.CHROMA_420);
      case CHROMA_SUBSAMPLING_422 -> Optional.of(ChromaSubsampling.CHROMA_422);
      case CHROMA_SUBSAMPLING_444 -> Optional.of(ChromaSubsampling.CHROMA_444);
      case CHROMA_SUBSAMPLING_MONOCHROME -> Optional.of(ChromaSubsampling.MONOCHROME);
      case CHROMA_SUBSAMPLING_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  private static OptionalInt rotation(ProbeStreamInfo stream) {
    if (!stream.hasRotationDegrees()) {
      return OptionalInt.empty();
    }

    var degrees = stream.getRotationDegrees();
    if (degrees < -MAXIMUM_ROTATION_DEGREES || degrees > MAXIMUM_ROTATION_DEGREES) {
      throw contractViolation("Worker probe rotation lies outside -180 to 180 degrees");
    }

    return OptionalInt.of(degrees);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ContainerCropping cropping(com.streamarr.transcode.v1.ContainerCropping crop) {
    return ContainerCropping.builder()
        .top(unsignedInt(crop.getTop(), "crop"))
        .bottom(unsignedInt(crop.getBottom(), "crop"))
        .left(unsignedInt(crop.getLeft(), "crop"))
        .right(unsignedInt(crop.getRight(), "crop"))
        .build();
  }

  private static Optional<String> codecString(ProbeStreamInfo stream) {
    if (!stream.hasCodecString()) {
      return Optional.empty();
    }

    if (stream.getCodecString().isBlank()) {
      throw contractViolation("Worker probe codec string is blank");
    }

    return Optional.of(stream.getCodecString());
  }

  private FrameRateRule frameRateRule(FrameRateRuleResult rule) {
    var basis = basis(rule.getBasis());
    if (rule.hasOutputFrameRate() == (basis == OutputFrameRateBasis.REFUSED)) {
      throw contractViolation(
          "Worker probe frame-rate rule pairs basis " + basis + " with the wrong output rate");
    }

    return FrameRateRule.builder()
        .outputFrameRate(
            rule.hasOutputFrameRate()
                ? Optional.of(fraction(rule.getOutputFrameRate()))
                : Optional.empty())
        .basis(basis)
        .inputs(inputs(rule.getInputs()))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static OutputFrameRateBasis basis(com.streamarr.transcode.v1.OutputFrameRateBasis basis) {
    return switch (basis) {
      case OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES -> OutputFrameRateBasis.DECLARED_RATES;
      case OUTPUT_FRAME_RATE_BASIS_MATROSKA_STATISTICS -> OutputFrameRateBasis.MATROSKA_STATISTICS;
      case OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN -> OutputFrameRateBasis.PACKET_SCAN;
      case OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK ->
          OutputFrameRateBasis.DECLARED_RATE_FALLBACK;
      case OUTPUT_FRAME_RATE_BASIS_REFUSED -> OutputFrameRateBasis.REFUSED;
      case OUTPUT_FRAME_RATE_BASIS_UNSPECIFIED, UNRECOGNIZED ->
          throw contractViolation("Worker probe frame-rate rule names no basis");
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private FrameRateRuleInputs inputs(com.streamarr.transcode.v1.FrameRateRuleInputs inputs) {
    return FrameRateRuleInputs.builder()
        .baseRate(
            inputs.hasBaseRate() ? Optional.of(fraction(inputs.getBaseRate())) : Optional.empty())
        .averageRate(
            inputs.hasAverageRate()
                ? Optional.of(fraction(inputs.getAverageRate()))
                : Optional.empty())
        .fieldOrder(fieldOrder(inputs.getFieldOrder()))
        .timeBase(
            inputs.hasTimeBase() ? Optional.of(fraction(inputs.getTimeBase())) : Optional.empty())
        .matroskaStatistics(matroskaStatistics(inputs.getMatroskaStatistics()))
        .packetScan(
            inputs.hasPacketScan()
                ? Optional.of(packetScan(inputs.getPacketScan()))
                : Optional.empty())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<FieldOrder> fieldOrder(com.streamarr.transcode.v1.FieldOrder order) {
    return switch (order) {
      case FIELD_ORDER_PROGRESSIVE -> Optional.of(FieldOrder.PROGRESSIVE);
      case FIELD_ORDER_TT -> Optional.of(FieldOrder.TT);
      case FIELD_ORDER_BB -> Optional.of(FieldOrder.BB);
      case FIELD_ORDER_TB -> Optional.of(FieldOrder.TB);
      case FIELD_ORDER_BT -> Optional.of(FieldOrder.BT);
      case FIELD_ORDER_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  // A message that carries neither tag reports the same fact as no message.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private Optional<MatroskaStatistics> matroskaStatistics(
      com.streamarr.transcode.v1.MatroskaStatistics statistics) {
    if (!statistics.hasNumberOfFrames() && !statistics.hasDuration()) {
      return Optional.empty();
    }

    if (statistics.hasNumberOfFrames() && statistics.getNumberOfFrames() < 0) {
      throw contractViolation("Worker probe frame count exceeds the supported range");
    }

    return Optional.of(
        new MatroskaStatistics(
            statistics.hasNumberOfFrames()
                ? OptionalLong.of(statistics.getNumberOfFrames())
                : OptionalLong.empty(),
            statistics.hasDuration()
                ? Optional.of(nonNegative(statistics.getDuration()))
                : Optional.empty()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private FrameRatePacketScan packetScan(com.streamarr.transcode.v1.FrameRatePacketScan scan) {
    if (scan.hasMotionRate()
        && !(Double.isFinite(scan.getMotionRate()) && scan.getMotionRate() > 0)) {
      throw contractViolation("Worker probe motion rate is not a positive number");
    }

    return FrameRatePacketScan.builder()
        .motionRate(
            scan.hasMotionRate() ? OptionalDouble.of(scan.getMotionRate()) : OptionalDouble.empty())
        .scannedFrameCount(unsignedInt(scan.getScannedFrameCount(), "scanned frame count"))
        .scannedDuration(
            scan.hasScannedDuration()
                ? Optional.of(nonNegative(scan.getScannedDuration()))
                : Optional.empty())
        .build();
  }

  private Duration nonNegative(DurationOrBuilder protobufDuration) {
    var duration = durations.apply(protobufDuration);
    if (duration.isNegative()) {
      throw contractViolation("Worker probe frame-rate duration is negative");
    }

    return duration;
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.domain.streaming.Fraction fraction(
      com.streamarr.transcode.v1.Fraction fraction) {
    if (fraction.getNumerator() <= 0 || fraction.getDenominator() <= 0) {
      throw contractViolation("Worker probe fraction has a term outside 1 to 2147483647");
    }

    return new com.streamarr.server.domain.streaming.Fraction(
        fraction.getNumerator(), fraction.getDenominator());
  }

  private static OptionalInt positive(boolean present, int value, String fact) {
    if (!present) {
      return OptionalInt.empty();
    }

    if (value <= 0) {
      throw contractViolation(WORKER_PROBE_PREFIX + fact + " is not a positive integer");
    }

    return OptionalInt.of(value);
  }

  private static OptionalInt unsigned(boolean present, int value, String fact) {
    return present ? OptionalInt.of(unsignedInt(value, fact)) : OptionalInt.empty();
  }

  // A protobuf uint32 above Integer.MAX_VALUE arrives as a negative int.
  private static int unsignedInt(int value, String fact) {
    if (value < 0) {
      throw contractViolation(WORKER_PROBE_PREFIX + fact + " exceeds the supported integer range");
    }

    return value;
  }

  private static ProbeContractViolationException contractViolation(String message) {
    return new ProbeContractViolationException(message);
  }
}
