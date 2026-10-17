package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Remote Worker Keepalive")
class RemoteWorkerKeepaliveIT {

  // gRPC allows no shorter keepalive time. The default keepalive would take at least 40 seconds
  // to close the connection.
  private static final WorkerSessionKeepalive SHORTEST_KEEPALIVE =
      new WorkerSessionKeepalive(Duration.ofSeconds(10), Duration.ofSeconds(1));

  @TempDir Path mediaRoot;

  @Test
  @DisplayName("Should release the session and capacity when the worker is frozen")
  void shouldReleaseSessionAndCapacityWhenWorkerIsFrozen() throws Exception {
    try (var server =
            WorkerSessionServer.builder()
                .configuration(serverConfigurationBuilder().keepalive(SHORTEST_KEEPALIVE).build())
                .segmentStore(new FakeSegmentStore())
                .meterRegistry(new SimpleMeterRegistry())
                .build();
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
            .atMost(25, TimeUnit.SECONDS)
            .untilAsserted(
                () -> assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse());
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
      } finally {
        worker.unpause();
      }
    }
  }
}
