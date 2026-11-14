package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.streamarr.server.domain.streaming.AssumedSourceMatrix;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ChannelLayout;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.transcode.v1.AudioChannelLayout;
import com.streamarr.transcode.v1.ChromaSubsampling;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.Deinterlace;
import com.streamarr.transcode.v1.DeinterlaceOutput;
import com.streamarr.transcode.v1.FieldOrder;
import com.streamarr.transcode.v1.MatrixCoefficients;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCodecProfile;
import com.streamarr.transcode.v1.VideoMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

// Domain and protobuf contracts intentionally share simple names.
final class RemoteVariantJobMapper {

  private final RemoteMediaSourceMapper sourceMapper;

  RemoteVariantJobMapper(UUID sourceNamespaceId, Path sourceRoot) {
    this.sourceMapper = new RemoteMediaSourceMapper(sourceNamespaceId, sourceRoot);
  }

  VariantJob map(TranscodeRequest request) {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(request.sessionId()))
        .setJobId(toProto(logicalJobId(request)))
        .setJobAttemptId(toProto(request.attemptId()))
        .setSource(sourceMapper.map(request.sourcePath()))
        .setDecision(decision(request))
        .setVariant(variant(request))
        .setExecution(execution(request))
        .build();
  }

  private static UUID logicalJobId(TranscodeRequest request) {
    var name = request.sessionId() + "\0" + request.variantLabel();
    return UUID.nameUUIDFromBytes(name.getBytes(UTF_8));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.TranscodeDecision decision(TranscodeRequest request) {
    var transcodeDecision = request.transcodeDecision();
    var video =
        switch (transcodeDecision.plannedVideoOutput()) {
          case PlannedVideoOutput.Encode planned -> encode(request, planned);
          case PlannedVideoOutput.StreamCopy _ ->
              copy(transcodeDecision, transcodeDecision.videoSource().orElseThrow());
        };
    var projection = decision(transcodeDecision);
    var audio =
        projection.getAudio().toBuilder()
            .setPadWithSilenceToAdvertisedEnd(padsWithSilence(request));
    return projection.toBuilder().setVideo(video).setAudio(audio).build();
  }

  // Only an encoded stream can hold its last frame or pad with silence; a copied stream keeps the
  // source's bytes (ADR 0037, early ends).
  private static boolean holdsLastFrame(TranscodeRequest request) {
    return request.advertisedEnd().isPresent()
        && !request.transcodeDecision().transcodeMode().copiesVideo();
  }

  private static boolean padsWithSilence(TranscodeRequest request) {
    return request.advertisedEnd().isPresent()
        && request.transcodeDecision().audioDecision().mode() == AudioMode.TRANSCODE;
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoDecision encode(
      TranscodeRequest request, PlannedVideoOutput.Encode planned) {
    var encode = request.videoEncodeDecision().orElseThrow();
    var source = request.transcodeDecision().videoSource();
    var video =
        com.streamarr.transcode.v1.VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_ENCODE)
            .setCodecFamily(codecFamily(encode.codecProfile()))
            .setCodecProfile(codecProfile(encode.codecProfile()))
            .setLevel(encode.level())
            .setCodecString(encode.codecString())
            .setSourceDynamicRange(
                RemoteDynamicRangeMapper.toProto(
                    source
                        .map(VideoPlaybackFacts::dynamicRange)
                        .orElse(
                            com.streamarr.server.domain.streaming.VideoDynamicRange.UNSPECIFIED)))
            .setOutputDynamicRange(RemoteDynamicRangeMapper.toProto(planned.dynamicRange()))
            .setOutputBitDepth(planned.bitDepth())
            // A planned encode refuses every chroma subsampling but 4:2:0 (#397).
            .setOutputChromaSubsampling(ChromaSubsampling.CHROMA_SUBSAMPLING_420)
            .setSoftwareEncoder(request.transcodeDecision().softwareEncoder())
            .setHoldLastFrameToAdvertisedEnd(holdsLastFrame(request));
    plannedStaticMetadata(video, planned);
    source.ifPresent(
        facts ->
            video
                .setStreamIndex(facts.streamIndex())
                .setSourceCodecFamily(sourceCodecFamily(facts))
                .setSourcePacketsCarryPresentationTimes(
                    sourcePacketsCarryPresentationTimes(facts)));
    encode.tier().map(RemoteVariantJobMapper::tier).ifPresent(video::setTier);
    request
        .transcodeDecision()
        .deinterlace()
        .map(RemoteVariantJobMapper::deinterlace)
        .ifPresent(video::setDeinterlace);
    request
        .transcodeDecision()
        .assumedSourceMatrix()
        .map(RemoteVariantJobMapper::matrixCoefficients)
        .ifPresent(video::setAssumedSourceMatrix);
    return video.build();
  }

  // BT.601 has two code points with the same matrix, and the job names it by code point 6.
  private static MatrixCoefficients matrixCoefficients(AssumedSourceMatrix assumed) {
    return switch (assumed) {
      case BT709 -> MatrixCoefficients.MATRIX_COEFFICIENTS_BT709;
      case BT601 -> MatrixCoefficients.MATRIX_COEFFICIENTS_SMPTE170M;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Deinterlace deinterlace(
      com.streamarr.server.domain.streaming.Deinterlace deinterlace) {
    return Deinterlace.newBuilder()
        .setFieldOrder(fieldOrder(deinterlace.fieldOrder()))
        .setOutput(deinterlaceOutput(deinterlace.output()))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static FieldOrder fieldOrder(
      com.streamarr.server.domain.streaming.FieldOrder fieldOrder) {
    return switch (fieldOrder) {
      case PROGRESSIVE -> FieldOrder.FIELD_ORDER_PROGRESSIVE;
      case TT -> FieldOrder.FIELD_ORDER_TT;
      case BB -> FieldOrder.FIELD_ORDER_BB;
      case TB -> FieldOrder.FIELD_ORDER_TB;
      case BT -> FieldOrder.FIELD_ORDER_BT;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DeinterlaceOutput deinterlaceOutput(
      com.streamarr.server.domain.streaming.DeinterlaceOutput output) {
    return switch (output) {
      case ONE_FRAME_PER_FIELD -> DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FIELD;
      case ONE_FRAME_PER_FRAME -> DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FRAME;
    };
  }

  // A copy forces no codec profile, tier or level.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoDecision copy(
      TranscodeDecision decision, VideoPlaybackFacts source) {
    var video =
        com.streamarr.transcode.v1.VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_COPY)
            .setCodecFamily(codecFamily(decision.videoCodecFamily()))
            .setCodecString(decision.copyCodecString().orElseThrow())
            .setSourceDynamicRange(RemoteDynamicRangeMapper.toProto(source.dynamicRange()))
            .setStreamIndex(source.streamIndex())
            .setSourceCodecFamily(sourceCodecFamily(source))
            .setSourcePacketsCarryPresentationTimes(sourcePacketsCarryPresentationTimes(source))
            .setCopyKeepsPacketBytes(copyKeepsPacketBytes(source));
    plannedCopyOutput(video, decision.plannedVideoOutput());
    return video.build();
  }

  // An SDR copy without rewrites passes the source's range through, so it carries no planned
  // output and still reaches a worker that predates the planned output (ADR 0013).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static void plannedCopyOutput(
      com.streamarr.transcode.v1.VideoDecision.Builder video, PlannedVideoOutput planned) {
    if (!(planned instanceof PlannedVideoOutput.StreamCopy copy)
        || !copy.requiresPlannedOutputSupport()) {
      return;
    }

    video.setOutputDynamicRange(RemoteDynamicRangeMapper.toProto(copy.dynamicRange()));
    copy.bitstreamRewrites().stream()
        .map(RemoteBitstreamRewriteMapper::toProto)
        .forEach(video::addBitstreamRewrites);
    copy.dolbyVisionOutput()
        .map(RemoteDolbyVisionOutputMapper::toProto)
        .ifPresent(video::setDolbyVisionOutput);
    copy.masteringDisplay()
        .map(RemoteVariantJobMapper::masteringDisplay)
        .ifPresent(video::setMasteringDisplay);
    copy.contentLightLevel()
        .map(RemoteVariantJobMapper::contentLightLevel)
        .ifPresent(video::setContentLightLevel);
  }

  // A PQ encode writes its static HDR metadata as HDR10 (#397).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static void plannedStaticMetadata(
      com.streamarr.transcode.v1.VideoDecision.Builder video, PlannedVideoOutput.Encode planned) {
    planned
        .masteringDisplay()
        .map(RemoteVariantJobMapper::masteringDisplay)
        .ifPresent(video::setMasteringDisplay);
    planned
        .contentLightLevel()
        .map(RemoteVariantJobMapper::contentLightLevel)
        .ifPresent(video::setContentLightLevel);
  }

  // The worker needs a feature for an encode's output range and bit depth, and for a PQ output
  // from Dolby Vision without a compatible base (#397).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static void plannedEncodeOutput(
      com.streamarr.transcode.v1.VideoDecision.Builder video, TranscodeDecision decision) {
    if (!(decision.plannedVideoOutput() instanceof PlannedVideoOutput.Encode planned)) {
      return;
    }

    video
        .setOutputDynamicRange(RemoteDynamicRangeMapper.toProto(planned.dynamicRange()))
        .setOutputBitDepth(planned.bitDepth());
    decision
        .videoSource()
        .map(VideoPlaybackFacts::dynamicRange)
        .map(RemoteDynamicRangeMapper::toProto)
        .ifPresent(video::setSourceDynamicRange);
  }

  // The domain values already use the units of the SEI fields and the contract (ADR 0036).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.MasteringDisplayColorVolume masteringDisplay(
      MasteringDisplayColorVolume display) {
    return com.streamarr.transcode.v1.MasteringDisplayColorVolume.newBuilder()
        .setRed(coordinates(display.red()))
        .setGreen(coordinates(display.green()))
        .setBlue(coordinates(display.blue()))
        .setWhitePoint(coordinates(display.whitePoint()))
        .setMaxLuminance((int) display.maxLuminance())
        .setMinLuminance((int) display.minLuminance())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.ChromaticityCoordinates coordinates(
      ChromaticityCoordinates coordinates) {
    return com.streamarr.transcode.v1.ChromaticityCoordinates.newBuilder()
        .setX(coordinates.x())
        .setY(coordinates.y())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.ContentLightLevel contentLightLevel(
      ContentLightLevel level) {
    return com.streamarr.transcode.v1.ContentLightLevel.newBuilder()
        .setMaxContentLightLevel(level.maxContentLightLevel())
        .setMaxFrameAverageLightLevel(level.maxFrameAverageLightLevel())
        .build();
  }

  // The projection that slot counts and execution targets read carries every video field that
  // decides which workers can run the job.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.transcode.v1.TranscodeDecision decision(TranscodeDecision decision) {
    var video =
        com.streamarr.transcode.v1.VideoDecision.newBuilder()
            .setMode(
                decision.transcodeMode().copiesVideo()
                    ? VideoMode.VIDEO_MODE_COPY
                    : VideoMode.VIDEO_MODE_ENCODE)
            .setCodecFamily(codecFamily(decision.videoCodecFamily()))
            .setSoftwareEncoder(decision.softwareEncoder());
    plannedCopyOutput(video, decision.plannedVideoOutput());
    plannedEncodeOutput(video, decision);
    return com.streamarr.transcode.v1.TranscodeDecision.newBuilder()
        .setMode(mode(decision.transcodeMode()))
        .setVideo(video)
        .setAudio(audio(decision))
        .setSubtitle(subtitle(decision.subtitleDecision()))
        // Every HLS variant is fMP4 (ADR 0037); the additive contract keeps the field.
        .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4)
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.AudioDecision audio(TranscodeDecision transcode) {
    var decision = transcode.audioDecision();
    var audio =
        com.streamarr.transcode.v1.AudioDecision.newBuilder()
            .setMode(audioMode(decision.mode()))
            .setChannels(decision.channels())
            .setBitrateBitsPerSecond(decision.bitrate());
    if (decision.codec() != null) {
      audio.setCodec(decision.codec());
    }

    decision
        .channelLayout()
        .map(RemoteVariantJobMapper::channelLayout)
        .ifPresent(audio::setChannelLayout);
    transcode.audioStreamIndex().ifPresent(audio::setStreamIndex);
    return audio.build();
  }

  private static AudioChannelLayout channelLayout(ChannelLayout layout) {
    return switch (layout) {
      case MONO -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_MONO;
      case STEREO -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO;
      case TWO_POINT_ONE -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE;
      case THREE_POINT_ZERO -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO;
      case FOUR_POINT_ZERO -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO;
      case QUAD -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD;
      case QUAD_SIDE -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD_SIDE;
      case FIVE_POINT_ZERO -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO;
      case FIVE_POINT_ZERO_SIDE -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE;
      case FIVE_POINT_ONE -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE;
      case FIVE_POINT_ONE_SIDE -> AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.SubtitleDecision subtitle(SubtitleDecision decision) {
    var subtitle =
        com.streamarr.transcode.v1.SubtitleDecision.newBuilder()
            .setMode(subtitleMode(decision.mode()));
    decision.codec().ifPresent(subtitle::setCodec);
    decision.streamIndex().ifPresent(subtitle::setStreamIndex);
    decision.language().ifPresent(subtitle::setLanguage);
    return subtitle.build();
  }

  private static VariantSpec variant(TranscodeRequest request) {
    return VariantSpec.newBuilder()
        .setVariantLabel(request.variantLabel())
        .setWidth(request.width())
        .setHeight(request.height())
        .setBitrateBitsPerSecond(request.bitrate())
        .setEncoderPreset(encoderPreset(request.encoderPreset()))
        .build();
  }

  private static TranscodeExecution execution(TranscodeRequest request) {
    var execution =
        TranscodeExecution.newBuilder()
            .setSeekPositionSeconds(request.seekPosition())
            .setTargetSegmentDurationSeconds(request.targetSegmentDuration())
            .setStartSequenceNumber(request.startSequenceNumber())
            .setMediaSegmentCount(request.mediaSegmentCount());
    request.framerate().ifPresent(execution::setFramerate);
    outputFrameRate(request).ifPresent(execution::setOutputFrameRate);
    request
        .keyframeSeekPosition()
        .ifPresent(position -> execution.setSeekPosition(protobufDuration(position)));
    request
        .exactSegmentStarts()
        .ifPresent(
            starts ->
                execution
                    .addAllSegmentStartTicks(starts.ticks())
                    .setSegmentStartTimeBase(fraction(starts.timeBase())));
    // Each job attempt carries the media end as the advertised end, a copy's included, and the
    // last listed segment's audio ends there (ADR 0037, media end).
    request.advertisedEnd().ifPresent(end -> execution.setAdvertisedEnd(protobufDuration(end)));
    return execution.build();
  }

  // The worker passes the exact fraction to FFmpeg. A copy keeps the source's timing and carries
  // none.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<com.streamarr.transcode.v1.Fraction> outputFrameRate(
      TranscodeRequest request) {
    return request.outputFrameRate().map(RemoteVariantJobMapper::fraction);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.Fraction fraction(Fraction fraction) {
    return com.streamarr.transcode.v1.Fraction.newBuilder()
        .setNumerator(fraction.numerator())
        .setDenominator(fraction.denominator())
        .build();
  }

  // A keyframe seek position and an advertised end are never negative, so their seconds and
  // nanoseconds share a sign as protobuf's Duration requires.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.google.protobuf.Duration protobufDuration(Duration duration) {
    return com.google.protobuf.Duration.newBuilder()
        .setSeconds(duration.getSeconds())
        .setNanos(duration.getNano())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.TranscodeMode mode(TranscodeMode mode) {
    return switch (mode) {
      case REMUX -> com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_REMUX;
      case AUDIO_TRANSCODE ->
          com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE;
      case VIDEO_TRANSCODE ->
          com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE;
      case FULL_TRANSCODE -> com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.EncoderPreset encoderPreset(EncoderPreset preset) {
    return switch (preset) {
      case SUPERFAST -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_SUPERFAST;
      case VERYFAST -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_VERYFAST;
      case FASTER -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_FASTER;
      case FAST -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_FAST;
      case MEDIUM -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_MEDIUM;
      case SLOW -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_SLOW;
      case SLOWER -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_SLOWER;
      case VERYSLOW -> com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_VERYSLOW;
    };
  }

  private static VideoCodecFamily codecFamily(String codecFamily) {
    return switch (codecFamily) {
      case "h264" -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case "hevc" -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case "av1" -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
      default ->
          throw new IllegalArgumentException(
              "The job contract has no video codec family " + codecFamily);
    };
  }

  private static VideoCodecFamily sourceCodecFamily(VideoPlaybackFacts source) {
    return source
        .codecFamily()
        .map(RemoteVariantJobMapper::sourceCodecFamily)
        .orElse(VideoCodecFamily.VIDEO_CODEC_FAMILY_UNSPECIFIED);
  }

  // The contract names no other source codec family.
  private static VideoCodecFamily sourceCodecFamily(ProbedCodecFamily codecFamily) {
    return switch (codecFamily) {
      case H264 -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case HEVC -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case AV1 -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
      case OTHER -> VideoCodecFamily.VIDEO_CODEC_FAMILY_UNSPECIFIED;
    };
  }

  // A source whose probe could not tell reads as one without presentation times.
  private static boolean sourcePacketsCarryPresentationTimes(VideoPlaybackFacts source) {
    return source
        .videoProperties()
        .flatMap(VideoStreamProperties::firstPacketsCarryPresentationTimes)
        .orElse(false);
  }

  // A source whose probe could not tell keeps the Annex B bitstream filter.
  private static boolean copyKeepsPacketBytes(VideoPlaybackFacts source) {
    return source
        .videoProperties()
        .flatMap(VideoStreamProperties::copyKeepsPacketBytes)
        .orElse(false);
  }

  private static VideoCodecFamily codecFamily(EncodeCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case HEVC_MAIN, HEVC_MAIN_10 -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case AV1_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
    };
  }

  private static VideoCodecProfile codecProfile(EncodeCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH;
      case HEVC_MAIN -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN;
      case HEVC_MAIN_10 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10;
      case AV1_MAIN -> VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoCodecTier tier(VideoCodecTier tier) {
    return switch (tier) {
      case MAIN -> com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_MAIN;
      case HIGH -> com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_HIGH;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.AudioMode audioMode(AudioMode mode) {
    return switch (mode) {
      case COPY -> com.streamarr.transcode.v1.AudioMode.AUDIO_MODE_COPY;
      case TRANSCODE -> com.streamarr.transcode.v1.AudioMode.AUDIO_MODE_TRANSCODE;
      case NONE -> com.streamarr.transcode.v1.AudioMode.AUDIO_MODE_NONE;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.SubtitleMode subtitleMode(SubtitleMode mode) {
    return switch (mode) {
      case EXCLUDE -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_EXCLUDE;
      case BURN_IN -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_BURN_IN;
      case SIDECAR -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_SIDECAR;
      case HLS -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_HLS;
      case EMBED -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_EMBED;
    };
  }
}
