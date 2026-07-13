package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.transcode.v1.RenditionJob;
import java.util.Optional;
import java.util.OptionalInt;

final class WorkerRenditionJobMapper {

  private final WorkerMediaSourceResolver sourceResolver;

  WorkerRenditionJobMapper(WorkerMediaSourceResolver sourceResolver) {
    this.sourceResolver = sourceResolver;
  }

  TranscodeRequest map(RenditionJob job) {
    var rendition = job.getRendition();
    var execution = job.getExecution();
    return TranscodeRequest.builder()
        .sessionId(fromProto(job.getStreamSessionId()))
        .sourcePath(sourceResolver.resolve(job.getSource()))
        .seekPosition(execution.getSeekPositionSeconds())
        .segmentDuration(execution.getSegmentDurationSeconds())
        .framerate(execution.getFramerate())
        .transcodeDecision(decision(job.getDecision()))
        .width(rendition.getWidth())
        .height(rendition.getHeight())
        .bitrate(rendition.getBitrateBitsPerSecond())
        .variantLabel(rendition.getRenditionName())
        .startNumber(execution.getStartNumber())
        .build();
  }

  private TranscodeDecision decision(com.streamarr.transcode.v1.TranscodeDecision decision) {
    return TranscodeDecision.builder()
        .transcodeMode(mode(decision.getMode()))
        .videoCodecFamily(decision.getVideoCodecFamily())
        .audioDecision(audio(decision.getAudio()))
        .subtitleDecision(subtitle(decision.getSubtitle()))
        .containerFormat(container(decision.getContainer()))
        .needsKeyframeAlignment(decision.getAlignKeyframesToSegments())
        .build();
  }

  private AudioDecision audio(com.streamarr.transcode.v1.AudioDecision audio) {
    return AudioDecision.builder()
        .mode(audioMode(audio.getMode()))
        .codec(audio.hasCodec() ? audio.getCodec() : null)
        .channels(audio.getChannels())
        .bitrate(audio.getBitrateBitsPerSecond())
        .build();
  }

  private SubtitleDecision subtitle(com.streamarr.transcode.v1.SubtitleDecision subtitle) {
    return new SubtitleDecision(
        subtitleMode(subtitle.getMode()),
        subtitle.hasCodec() ? Optional.of(subtitle.getCodec()) : Optional.empty(),
        subtitle.hasStreamIndex() ? OptionalInt.of(subtitle.getStreamIndex()) : OptionalInt.empty(),
        subtitle.hasLanguage() ? Optional.of(subtitle.getLanguage()) : Optional.empty());
  }

  private TranscodeMode mode(com.streamarr.transcode.v1.TranscodeMode mode) {
    return switch (mode) {
      case TRANSCODE_MODE_REMUX -> TranscodeMode.REMUX;
      case TRANSCODE_MODE_AUDIO_TRANSCODE -> TranscodeMode.AUDIO_TRANSCODE;
      case TRANSCODE_MODE_VIDEO_TRANSCODE -> TranscodeMode.VIDEO_TRANSCODE;
      case TRANSCODE_MODE_FULL_TRANSCODE -> TranscodeMode.FULL_TRANSCODE;
      case TRANSCODE_MODE_UNSPECIFIED, UNRECOGNIZED ->
          throw new WorkerJobException("Transcode mode is required");
    };
  }

  private AudioMode audioMode(com.streamarr.transcode.v1.AudioMode mode) {
    return switch (mode) {
      case AUDIO_MODE_COPY -> AudioMode.COPY;
      case AUDIO_MODE_TRANSCODE -> AudioMode.TRANSCODE;
      case AUDIO_MODE_NONE -> AudioMode.NONE;
      case AUDIO_MODE_UNSPECIFIED, UNRECOGNIZED ->
          throw new WorkerJobException("Audio mode is required");
    };
  }

  private SubtitleMode subtitleMode(com.streamarr.transcode.v1.SubtitleMode mode) {
    return switch (mode) {
      case SUBTITLE_MODE_EXCLUDE -> SubtitleMode.EXCLUDE;
      case SUBTITLE_MODE_BURN_IN -> SubtitleMode.BURN_IN;
      case SUBTITLE_MODE_SIDECAR -> SubtitleMode.SIDECAR;
      case SUBTITLE_MODE_HLS -> SubtitleMode.HLS;
      case SUBTITLE_MODE_EMBED -> SubtitleMode.EMBED;
      case SUBTITLE_MODE_UNSPECIFIED, UNRECOGNIZED ->
          throw new WorkerJobException("Subtitle mode is required");
    };
  }

  private ContainerFormat container(com.streamarr.transcode.v1.ContainerFormat container) {
    return switch (container) {
      case CONTAINER_FORMAT_MPEG_TS -> ContainerFormat.MPEGTS;
      case CONTAINER_FORMAT_FMP4 -> ContainerFormat.FMP4;
      case CONTAINER_FORMAT_UNSPECIFIED, UNRECOGNIZED ->
          throw new WorkerJobException("Container format is required");
    };
  }
}
