package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

final class RemoteVariantJobMapper {

  private final UUID sourceNamespaceId;
  private final Path sourceRoot;

  RemoteVariantJobMapper(UUID sourceNamespaceId, Path sourceRoot) {
    this.sourceNamespaceId = sourceNamespaceId;
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
  }

  VariantJob map(TranscodeRequest request) {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(request.sessionId()))
        .setJobId(toProto(logicalJobId(request)))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(source(request.sourcePath()))
        .setDecision(decision(request))
        .setVariant(variant(request))
        .setExecution(execution(request))
        .build();
  }

  private static UUID logicalJobId(TranscodeRequest request) {
    var name = request.sessionId() + "\0" + request.variantLabel();
    return UUID.nameUUIDFromBytes(name.getBytes(UTF_8));
  }

  private MediaSourceRef source(Path sourcePath) {
    var normalized = sourcePath.toAbsolutePath().normalize();
    if (!normalized.startsWith(sourceRoot) || normalized.equals(sourceRoot)) {
      throw new TranscodeException("Media source is outside the configured source namespace");
    }

    var relativeKey = sourceRoot.relativize(normalized).toString().replace(File.separatorChar, '/');
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(sourceNamespaceId))
        .setRelativeKey(relativeKey)
        .build();
  }

  private static com.streamarr.transcode.v1.TranscodeDecision decision(TranscodeRequest request) {
    var decision = request.transcodeDecision();
    return com.streamarr.transcode.v1.TranscodeDecision.newBuilder()
        .setMode(mode(decision.transcodeMode()))
        .setVideoCodecFamily(decision.videoCodecFamily())
        .setAudio(audio(decision.audioDecision()))
        .setSubtitle(subtitle(decision.subtitleDecision()))
        .setContainer(container(decision.containerFormat()))
        .setAlignKeyframesToSegments(decision.needsKeyframeAlignment())
        .build();
  }

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
        .build();
  }

  private static TranscodeExecution execution(TranscodeRequest request) {
    return TranscodeExecution.newBuilder()
        .setSeekPositionSeconds(request.seekPosition())
        .setSegmentDurationSeconds(request.segmentDuration())
        .setFramerate(request.framerate())
        .setStartSequenceNumber(request.startNumber())
        .build();
  }

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

  private static com.streamarr.transcode.v1.AudioMode audioMode(AudioMode mode) {
    return switch (mode) {
      case COPY -> com.streamarr.transcode.v1.AudioMode.AUDIO_MODE_COPY;
      case TRANSCODE -> com.streamarr.transcode.v1.AudioMode.AUDIO_MODE_TRANSCODE;
      case NONE -> com.streamarr.transcode.v1.AudioMode.AUDIO_MODE_NONE;
    };
  }

  private static com.streamarr.transcode.v1.SubtitleMode subtitleMode(SubtitleMode mode) {
    return switch (mode) {
      case EXCLUDE -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_EXCLUDE;
      case BURN_IN -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_BURN_IN;
      case SIDECAR -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_SIDECAR;
      case HLS -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_HLS;
      case EMBED -> com.streamarr.transcode.v1.SubtitleMode.SUBTITLE_MODE_EMBED;
    };
  }

  private static com.streamarr.transcode.v1.ContainerFormat container(ContainerFormat format) {
    return switch (format) {
      case MPEGTS -> com.streamarr.transcode.v1.ContainerFormat.CONTAINER_FORMAT_MPEG_TS;
      case FMP4 -> com.streamarr.transcode.v1.ContainerFormat.CONTAINER_FORMAT_FMP4;
    };
  }
}
