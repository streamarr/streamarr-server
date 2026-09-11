package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionListeners;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegProcessManager;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.TranscodeWorker;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import lombok.Builder;

final class WorkerStreamingSmokeFixture implements AutoCloseable {

  private final UUID sourceNamespaceId = UUID.randomUUID();
  private final WorkerSessionServer workerSessions;
  private final TranscodeWorker worker;

  @Builder
  private WorkerStreamingSmokeFixture(
      FfmpegProcessManager processManager,
      Path sourceRoot,
      Path segmentBaseDir,
      LocalSegmentStore segmentStore) {
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg", command -> new ProcessBuilder(command).redirectErrorStream(false).start());
    capabilityService.detectCapabilities();
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"), processManager, capabilityService);
    workerSessions =
        WorkerSessionServer.forListeners(
            WorkerSessionListeners.builder().loopbackPort(OptionalInt.of(0)).build(), segmentStore);
    worker =
        new TranscodeWorker(
            TranscodeWorkerConfiguration.builder()
                .workerId(UUID.randomUUID())
                .bootId(UUID.randomUUID())
                .availableSlots(3)
                .plaintext(true)
                .sourceNamespaces(Map.of(sourceNamespaceId, sourceRoot))
                .segmentBasePath(segmentBaseDir.resolve("worker"))
                .build(),
            engine,
            FfprobeExecutor.forBinary(Path.of("ffprobe")));
  }

  void start() throws Exception {
    workerSessions.start();
    worker.start("127.0.0.1", workerSessions.loopbackPort());
    await()
        .untilAsserted(
            () -> assertThat(workerSessions.availableSlots(sourceNamespaceId)).isEqualTo(3));
  }

  WorkerSessionServer workerSessions() {
    return workerSessions;
  }

  UUID sourceNamespaceId() {
    return sourceNamespaceId;
  }

  @Override
  public void close() {
    worker.close();
    workerSessions.close();
  }
}
