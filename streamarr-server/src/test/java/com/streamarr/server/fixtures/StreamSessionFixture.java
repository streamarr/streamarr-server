package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.transcode.engine.model.AudioDecision;
import com.streamarr.transcode.engine.model.ContainerFormat;
import com.streamarr.transcode.engine.model.QualityVariant;
import com.streamarr.transcode.engine.model.SubtitleDecision;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StreamSessionFixture {

  private StreamSessionFixture() {}

  public static StreamSession.StreamSessionBuilder defaultSessionBuilder() {
    return StreamSession.builder()
        .sessionId(UUID.randomUUID())
        .mediaFileId(UUID.randomUUID())
        .profileId(UUID.randomUUID())
        .mediaProbe(defaultProbeBuilder().build())
        .transcodeDecision(remuxMpegtsDecision())
        .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
        .createdAt(Instant.now());
  }

  public static MediaProbe.MediaProbeBuilder defaultProbeBuilder() {
    return MediaProbe.builder()
        .duration(Duration.ofMinutes(120))
        .framerate(24.0)
        .width(1920)
        .height(1080)
        .videoCodec("h264")
        .audioCodec("aac")
        .bitrate(5_000_000);
  }

  public static TranscodeDecision remuxMpegtsDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.copy("aac", 2, 0))
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.MPEGTS)
        .needsKeyframeAlignment(true)
        .build();
  }

  public static TranscodeDecision fullTranscodeDecision(
      String videoCodecFamily, ContainerFormat containerFormat) {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily(videoCodecFamily)
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(containerFormat)
        .needsKeyframeAlignment(false)
        .build();
  }

  public static QualityVariant.QualityVariantBuilder defaultVariantBuilder() {
    return QualityVariant.builder().audioBitrate(128_000L);
  }

  public static StreamSession buildMpegtsSession() {
    return buildMpegtsSessionOwnedBy(UUID.randomUUID());
  }

  public static StreamSession buildMpegtsSessionOwnedBy(UUID profileId) {
    return defaultSessionBuilder().profileId(profileId).build();
  }

  public static StreamSession.StreamSessionBuilder sessionWithDurationBuilder(int durationSeconds) {
    return defaultSessionBuilder()
        .mediaProbe(defaultProbeBuilder().duration(Duration.ofSeconds(durationSeconds)).build());
  }

  public static StreamSession.StreamSessionBuilder zeroDurationSessionBuilder() {
    return defaultSessionBuilder()
        .mediaProbe(
            defaultProbeBuilder()
                .duration(Duration.ZERO)
                .framerate(0)
                .width(0)
                .height(0)
                .bitrate(0)
                .build());
  }
}
