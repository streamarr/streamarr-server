package com.streamarr.server.services.streaming.ffmpeg;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Local Transcode Executor Tests")
class LocalTranscodeExecutorTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should create variant subdirectory when variant label is provided")
  void shouldCreateVariantSubdirectoryWhenVariantLabelProvided() {
    var executor =
        new LocalTranscodeExecutor(
            remuxEngine(new FakeFfmpegProcessManager()), new LocalSegmentStore(tempDir));
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .targetSegmentDuration(6)
            .framerate(23.976)
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .containerFormat(ContainerFormat.MPEGTS)
                    .needsKeyframeAlignment(false)
                    .build())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000L)
            .variantLabel("720p")
            .build();

    var handle = executor.start(request);

    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(executor.isRunning(request.sessionId(), "720p")).isTrue();

    var variantDir = tempDir.resolve(request.sessionId().toString()).resolve("720p");
    assertThat(variantDir).exists().isDirectory();
  }
}
