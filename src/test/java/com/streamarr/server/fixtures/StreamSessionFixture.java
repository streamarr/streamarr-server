package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
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

  public static StreamSession buildMpegtsSession() {
    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .duration(Duration.ofMinutes(120))
                    .framerate(24.0)
                    .width(1920)
                    .height(1080)
                    .videoCodec("h264")
                    .audioCodec("aac")
                    .bitrate(5_000_000)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.REMUX)
                    .videoCodecFamily("h264")
                    .audioCodec("aac")
                    .containerFormat(ContainerFormat.MPEGTS)
                    .needsKeyframeAlignment(true)
                    .build())
            .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
            .createdAt(Instant.now())
            .lastAccessedAt(Instant.now())
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }
}
