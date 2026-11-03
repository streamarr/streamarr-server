package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncoderPreset;
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
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.Deinterlace;
import com.streamarr.transcode.v1.DeinterlaceOutput;
import com.streamarr.transcode.v1.FieldOrder;
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
    return decision(transcodeDecision).toBuilder().setVideo(video).build();
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
            .setOutputDynamicRange(RemoteDynamicRangeMapper.toProto(planned.dynamicRange()));
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
    return video.build();
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

  // A copy forces no codec profile, tier or level. The job carries a copy's planned output range
  // only once the worker contract accepts one (streamarr/streamarr-server#396, ADR 0013).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoDecision copy(
      TranscodeDecision decision, VideoPlaybackFacts source) {
    return com.streamarr.transcode.v1.VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_COPY)
        .setCodecFamily(codecFamily(decision.videoCodecFamily()))
        .setCodecString(source.codecString().orElseThrow())
        .setSourceDynamicRange(RemoteDynamicRangeMapper.toProto(source.dynamicRange()))
        .setStreamIndex(source.streamIndex())
        .setSourceCodecFamily(sourceCodecFamily(source))
        .setSourcePacketsCarryPresentationTimes(sourcePacketsCarryPresentationTimes(source))
        .setCopyKeepsPacketBytes(copyKeepsPacketBytes(source))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.transcode.v1.TranscodeDecision decision(TranscodeDecision decision) {
    return com.streamarr.transcode.v1.TranscodeDecision.newBuilder()
        .setMode(mode(decision.transcodeMode()))
        .setVideo(
            com.streamarr.transcode.v1.VideoDecision.newBuilder()
                .setMode(
                    decision.transcodeMode().copiesVideo()
                        ? VideoMode.VIDEO_MODE_COPY
                        : VideoMode.VIDEO_MODE_ENCODE)
                .setCodecFamily(codecFamily(decision.videoCodecFamily())))
        .setAudio(audio(decision.audioDecision()))
        .setSubtitle(subtitle(decision.subtitleDecision()))
        // Every HLS variant is fMP4 (ADR 0037); the additive contract keeps the field.
        .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4)
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.AudioDecision audio(AudioDecision decision) {
    var audio =
        com.streamarr.transcode.v1.AudioDecision.newBuilder()
            .setMode(audioMode(decision.mode()))
            .setChannels(decision.channels())
            .setBitrateBitsPerSecond(decision.bitrate());
    if (decision.codec() != null) {
      audio.setCodec(decision.codec());
    }
    return audio.build();
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
    return execution.build();
  }

  // The worker passes the exact fraction to FFmpeg. A copy keeps the source's timing and carries
  // none.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<com.streamarr.transcode.v1.Fraction> outputFrameRate(
      TranscodeRequest request) {
    return request
        .outputFrameRate()
        .map(
            rate ->
                com.streamarr.transcode.v1.Fraction.newBuilder()
                    .setNumerator(rate.numerator())
                    .setDenominator(rate.denominator())
                    .build());
  }

  // The keyframe seek position is never negative, so its seconds and nanoseconds share a sign as
  // protobuf's Duration requires.
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
      case AV1_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
    };
  }

  private static VideoCodecProfile codecProfile(EncodeCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH;
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
