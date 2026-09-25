package com.streamarr.server.services.streaming;

import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.UUID;
import lombok.Builder;

final class WorkerStreamingSmokeFixture implements AutoCloseable {
  private final UUID sourceNamespaceId = UUID.randomUUID();
  private final WorkerSessionServer workerSessions;
  private final WorkerContainerFixture worker;

  @Builder
  private WorkerStreamingSmokeFixture(
      Path sourceRoot, LocalSegmentStore segmentStore, String ffmpegScript) {
    workerSessions =
        new WorkerSessionServer(
            WorkerSessionServerConfiguration.builder().address("127.0.0.1").port(0).build(),
            segmentStore,
            new SimpleMeterRegistry());
    worker =
        WorkerContainerFixture.builder()
            .workerSessions(workerSessions)
            .sourceNamespaceId(sourceNamespaceId)
            .sourceRoot(sourceRoot)
            .availableSlots(3)
            .ffmpegScript(ffmpegScript)
            .build();
  }

  void start() throws Exception {
    workerSessions.start();
    worker.start();
  }

  WorkerSessionServer workerSessions() {
    return workerSessions;
  }

  UUID sourceNamespaceId() {
    return sourceNamespaceId;
  }

  WorkerContainerFixture worker() {
    return worker;
  }

  @Override
  public void close() {
    try {
      worker.close();
    } finally {
      workerSessions.close();
    }
  }
}
