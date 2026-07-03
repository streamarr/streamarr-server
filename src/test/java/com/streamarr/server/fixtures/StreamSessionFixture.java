package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import java.nio.file.Path;
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
        .sourcePath(Path.of("/media/movie.mkv"))
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

  public static StreamSession withActiveVariantHandles(StreamSession session) {
    for (var variant : session.getVariants()) {
      session.setVariantHandle(variant.label(), new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    }
    return session;
  }

  public static StreamSession buildMpegtsSession() {
    var session = defaultSessionBuilder().build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  public static StreamSession buildSessionWithDuration(int durationSeconds) {
    return defaultSessionBuilder()
        .mediaProbe(defaultProbeBuilder().duration(Duration.ofSeconds(durationSeconds)).build())
        .build();
  }

  public static StreamSession buildSessionForMediaFile(UUID mediaFileId) {
    return defaultSessionBuilder().mediaFileId(mediaFileId).build();
  }

  public static StreamSession buildZeroDurationSession() {
    return defaultSessionBuilder()
        .sourcePath(Path.of("/media/corrupt.mkv"))
        .mediaProbe(
            defaultProbeBuilder()
                .duration(Duration.ZERO)
                .framerate(0)
                .width(0)
                .height(0)
                .bitrate(0)
                .build())
        .build();
  }
}
