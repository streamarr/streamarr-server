package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoEncodeDecision;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

// Domain and protobuf contracts intentionally share simple names.
final class RemoteVariantJobMapper {

  // Every encode's planned output is SDR without HDR10+ or Dolby Vision (ADR 0013).
  private static final VideoDynamicRange SDR_OUTPUT_DYNAMIC_RANGE =
      VideoDynamicRange.newBuilder()
          .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
          .setHdr10Plus(Presence.PRESENCE_ABSENT)
          .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
          .build();

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
    var decision = decision(request.transcodeDecision()).toBuilder();
    var source = request.transcodeDecision().videoSource();
    var encode = request.videoEncodeDecision();
    if (encode.isPresent()) {
      return decision.setVideo(encode(encode.get(), source)).build();
    }

    // A copy of a source that its probe gave no playback facts carries no video decision, as
    // before probe version 4.
    source.ifPresent(facts -> decision.setVideo(copy(request.transcodeDecision(), facts)));
    return decision.build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoDecision encode(
      VideoEncodeDecision encode, Optional<VideoPlaybackFacts> source) {
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
            .setOutputDynamicRange(SDR_OUTPUT_DYNAMIC_RANGE);
    source.ifPresent(facts -> video.setStreamIndex(facts.streamIndex()));
    encode.tier().map(RemoteVariantJobMapper::tier).ifPresent(video::setTier);
    return video.build();
  }

  // A copy forces no codec profile, tier or level, and carries no planned output range until
  // streamarr/streamarr-server#396 settles one (ADR 0013).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoDecision copy(
      TranscodeDecision decision, VideoPlaybackFacts source) {
    return com.streamarr.transcode.v1.VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_COPY)
        .setCodecFamily(codecFamily(decision.videoCodecFamily()))
        .setCodecString(source.codecString().orElseThrow())
        .setSourceDynamicRange(RemoteDynamicRangeMapper.toProto(source.dynamicRange()))
        .setStreamIndex(source.streamIndex())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.transcode.v1.TranscodeDecision decision(TranscodeDecision decision) {
    return com.streamarr.transcode.v1.TranscodeDecision.newBuilder()
        .setMode(mode(decision.transcodeMode()))
        .setVideoCodecFamily(decision.videoCodecFamily())
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
    var decision = request.transcodeDecision();
    if (decision.transcodeMode().copiesVideo()) {
      return Optional.empty();
    }

    return decision
        .videoSource()
        .flatMap(facts -> facts.frameRateRule().outputFrameRate())
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

  private static VideoCodecFamily codecFamily(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case AV1_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoCodecProfile codecProfile(
      VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH;
      case AV1_MAIN -> com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.VideoCodecTier tier(VideoCodecTier tier) {
    return switch (tier) {
      case MAIN -> com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_MAIN;
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
