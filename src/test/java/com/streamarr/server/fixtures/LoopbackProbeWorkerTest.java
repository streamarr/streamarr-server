package com.streamarr.server.fixtures;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Loopback Probe Worker Tests")
class LoopbackProbeWorkerTest {

  @Test
  @DisplayName("Should end the worker session when closing is interrupted")
  void shouldEndTheWorkerSessionWhenClosingIsInterrupted() throws Exception {
    try (var server = server()) {
      server.start();
      var worker = worker(server);
      try {
        Thread.currentThread().interrupt();
        assertThatThrownBy(worker::close).isExactlyInstanceOf(InterruptedException.class);
        assertThat(worker.terminated().get(5, TimeUnit.SECONDS)).isNotNull();
      } finally {
        Thread.interrupted();
        worker.disconnect();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  @DisplayName("Should reject an invalid message size when sending a report")
  void shouldRejectAnInvalidMessageSizeWhenSendingAReport(int messageBytes) throws Exception {
    try (var server = server()) {
      server.start();
      try (var worker = worker(server)) {
        var report =
            ProbeAttemptReport.newBuilder()
                .setResult(
                    ProbeAttemptResult.newBuilder().setProbeAttemptId(toProto(UUID.randomUUID())))
                .build();

        assertThatThrownBy(() -> worker.report(report, messageBytes))
            .isInstanceOf(AssertionError.class);
        assertThat(worker.terminated()).isNotDone();
      }
    }
  }

  private static WorkerSessionServer server() {
    return WorkerSessionServer.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(serverConfigurationBuilder().build())
        .segmentStore(new FakeSegmentStore())
        .meterRegistry(new SimpleMeterRegistry())
        .build();
  }

  private static LoopbackProbeWorker worker(WorkerSessionServer server) throws Exception {
    return LoopbackProbeWorker.builder()
        .port(server.port())
        .probeVersion(ProbeVersion.CURRENT)
        .backgroundProbeCapacity(1)
        .build();
  }
}
