package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Remote Worker Keepalive")
class RemoteWorkerKeepaliveIT {
  @TempDir Path mediaRoot;

  @Test
  @DisplayName("Should register before readiness and release the session when the worker is frozen")
  void shouldRegisterBeforeReadinessAndReleaseSessionWhenWorkerIsFrozen() throws Exception {
    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker =
            WorkerContainerFixture.builder()
                .workerSessions(server)
                .sourceNamespaceId(SOURCE_NAMESPACE_ID)
                .sourceRoot(mediaRoot)
                .build()) {
      server.start();
      worker.start();
      assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
      assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
      worker.pause();
      try {
        await()
            .atMost(60, TimeUnit.SECONDS)
            .untilAsserted(
                () -> assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse());
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
      } finally {
        worker.unpause();
      }
    }
  }
}
