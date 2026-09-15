package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sourceBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.probe.FfprobeExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Worker Probe Capacity Integration Tests")
class TranscodeWorkerProbeIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should retain the execution slot until ffprobe exits when cancelling a probe")
  void shouldRetainTheExecutionSlotUntilFfprobeExitsWhenCancellingAProbe() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("held.mkv"), "media");
    Files.writeString(mediaRoot.resolve("next.mkv"), "media");
    var process = processBuilder().running(true).deferredTermination(true).build();
    var ffprobe =
        new FfprobeExecutor(
            new ObjectMapper(),
            source ->
                source.getFileName().toString().equals("held.mkv")
                    ? process
                    : processBuilder().build());
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("held.mkv")).build();
    var nextRequest =
        requestBuilder().setSource(sourceBuilder().setRelativeKey("next.mkv")).build();
    var configuration =
        workerConfigurationBuilder()
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("segments"))
            .build();

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker =
            TranscodeWorker.builder()
                .configuration(configuration)
                .engine(remuxEngine(new FakeFfmpegProcessManager()))
                .ffprobe(ffprobe)
                .build()) {
      try {
        server.start();
        worker.start("localhost", server.port());
        var pending = server.dispatchProbe(request).orElseThrow();
        process.awaitStarted();

        assertThat(pending.cancel(true)).isTrue();

        process.awaitTerminationRequested();
        assertThat(process.isAlive()).isTrue();
        assertThat(server.dispatchProbe(nextRequest)).isEmpty();
      } finally {
        process.finish();
      }

      var next =
          await()
              .atMost(5, TimeUnit.SECONDS)
              .until(() -> server.dispatchProbe(nextRequest), Optional::isPresent)
              .orElseThrow();
      var result = next.get(5, TimeUnit.SECONDS);

      assertThat(process.isAlive()).isFalse();
      assertThat(result.getProbeAttemptId()).isEqualTo(nextRequest.getProbeAttemptId());
      assertThat(result.hasMedia()).isTrue();
    }
  }
}
