package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionListeners;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Plaintext Transcode Worker Integration Tests")
class PlaintextTranscodeWorkerIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should connect without certificates when plaintext is explicitly enabled")
  void shouldConnectWithoutCertificatesWhenPlaintextIsExplicitlyEnabled() throws Exception {
    var sourceId = UUID.randomUUID();
    var workerId = UUID.randomUUID();
    var listeners = WorkerSessionListeners.builder().loopbackPort(OptionalInt.of(0)).build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();
      var settings =
          TranscodeWorkerSettings.fromEnvironment(
              Map.of(
                  "TRANSCODE_WORKER_PLAINTEXT", "true",
                  "TRANSCODE_WORKER_ID", workerId.toString(),
                  "TRANSCODE_WORKER_CONTROL_PLANE_HOST", "127.0.0.1",
                  "TRANSCODE_WORKER_CONTROL_PLANE_PORT", Integer.toString(server.loopbackPort()),
                  "TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", sourceId.toString(),
                  "TRANSCODE_WORKER_SOURCE_ROOT", tempDir.toString()));

      try (var worker =
          new TranscodeWorker(
              settings.workerConfiguration(), remuxEngine(new FakeFfmpegProcessManager()))) {
        worker.start(settings.controlPlaneHost(), settings.controlPlanePort());

        assertThat(settings.workerConfiguration().workerId()).isEqualTo(workerId);
        assertThat(server.availableSlots(sourceId)).isEqualTo(1);
      }
    }
  }
}
