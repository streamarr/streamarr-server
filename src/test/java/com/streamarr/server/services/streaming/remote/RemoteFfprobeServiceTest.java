package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.ProbeCancelReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;

@Tag("UnitTest")
@DisplayName("Remote Ffprobe Service Tests")
class RemoteFfprobeServiceTest {

  private static final Duration BOUND = Duration.ofSeconds(5);
  // Shorter than any wait for the worker's start command, so the timeout has passed by the time
  // the test reads the next command. The outcome never depends on when the timeout fires.
  private static final Duration ELAPSED_TIMEOUT = Duration.ofNanos(1);

  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(
      strings = {".", "../outside.mkv", "nested/../../outside.mkv", "../movies-extra/film.mkv"})
  @DisplayName("Should reject probing when the source is not a file within its namespace")
  void shouldRejectProbingWhenSourceIsNotFileWithinItsNamespace(String relativePath) {
    var sourceRoot = tempDir.resolve("movies");
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot);
      var request =
          ProbeExecutionRequest.builder()
              .attemptId(UUID.randomUUID())
              .probeVersion(ProbeVersion.CURRENT)
              .sourcePath(sourceRoot.resolve(relativePath))
              .priority(ProbePriority.BACKGROUND)
              .build();

      assertThatThrownBy(() -> service.probe(request))
          .isExactlyInstanceOf(TranscodeException.class)
          .hasMessage("Media source is outside the configured source namespace");
    }
  }

  @ParameterizedTest
  @EnumSource(ProbePriority.class)
  @DisplayName("Should defer the probe without a failure when no worker is connected")
  void shouldDeferTheProbeWithoutAFailureWhenNoWorkerIsConnected(ProbePriority priority)
      throws IOException {
    var sourceRoot = Files.createDirectories(tempDir.resolve("movies"));
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      server.start();
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot);
      var request =
          ProbeExecutionRequest.builder()
              .attemptId(UUID.randomUUID())
              .probeVersion(ProbeVersion.CURRENT)
              .sourcePath(Files.createFile(sourceRoot.resolve("film.mkv")))
              .priority(priority)
              .build();

      assertThatThrownBy(() -> service.probe(request))
          .isExactlyInstanceOf(ProbeWorkersBusyException.class)
          .hasMessage(ProbeRefusal.NO_CONNECTED_WORKER.description());
    }
  }

  @ParameterizedTest
  @EnumSource(ProbePriority.class)
  @DisplayName(
      "Should tell the worker that the probe timed out when the probe reaches the probe timeout")
  void shouldTellWorkerProbeTimedOutWhenProbeReachesProbeTimeout(ProbePriority priority)
      throws Exception {
    var configuration = serverConfigurationBuilder().probeTimeout(ELAPSED_TIMEOUT).build();
    try (var server = server(configuration)) {
      server.start();
      try (var worker = worker(server)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot());
        var request = request(priority);

        try (var probe = BoundedTask.start(() -> service.probe(request))) {
          assertThat(worker.nextResponse().hasStartProbe()).isTrue();

          assertThatThrownBy(() -> probe.await(BOUND))
              .isInstanceOfSatisfying(
                  ProbeTimedOutException.class,
                  failure -> assertThat(failure.reason()).isEqualTo(ItemFailureReason.TEMPORARY));
          assertThat(withoutTarget(worker.nextResponse().getCancelProbe()))
              .isEqualTo(cancel(request, ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT));
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Should tell the worker that the scan timed out when a keyframe packet scan reaches the scan timeout")
  void shouldTellWorkerScanTimedOutWhenKeyframePacketScanReachesScanTimeout() throws Exception {
    var configuration = serverConfigurationBuilder().keyframeScanTimeout(ELAPSED_TIMEOUT).build();
    try (var server = server(configuration)) {
      server.start();
      try (var worker = worker(server)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot());
        var request = request(ProbePriority.BACKGROUND);

        try (var scan = BoundedTask.start(() -> service.scanKeyframePackets(request))) {
          assertThat(worker.nextResponse().getStartProbe().getRequest().getScanKeyframePackets())
              .isTrue();

          assertThatThrownBy(() -> scan.await(BOUND)).isInstanceOf(ProbeTimedOutException.class);
          assertThat(withoutTarget(worker.nextResponse().getCancelProbe()))
              .isEqualTo(cancel(request, ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT));
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Should tell the worker another reason when the server stops waiting for a probe while running")
  void shouldTellWorkerAnotherReasonWhenServerStopsWaitingForProbeWhileRunning() throws Exception {
    try (var server = server(serverConfigurationBuilder().build())) {
      server.start();
      try (var worker = worker(server)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot());
        var request = request(ProbePriority.BACKGROUND);

        try (var probe = BoundedTask.start(() -> service.probe(request))) {
          assertThat(worker.nextResponse().hasStartProbe()).isTrue();

          probe.interrupt();

          assertThatThrownBy(() -> probe.await(BOUND)).isInstanceOf(ProbeCancelledException.class);
          assertThat(withoutTarget(worker.nextResponse().getCancelProbe()))
              .isEqualTo(cancel(request, ProbeCancelReason.PROBE_CANCEL_REASON_OTHER));
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Should tell the worker that the server is shutting down when the application stops a probe during shutdown")
  void shouldTellWorkerServerIsShuttingDownWhenApplicationStopsProbeDuringShutdown()
      throws Exception {
    try (var server = server(serverConfigurationBuilder().build())) {
      server.start();
      try (var worker = worker(server)) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot());
        var request = request(ProbePriority.BACKGROUND);

        try (var probe = BoundedTask.start(() -> service.probe(request))) {
          assertThat(worker.nextResponse().hasStartProbe()).isTrue();
          var stopping = new ProbeStoppedAtShutdown(probe, worker);

          try (var context = new GenericApplicationContext()) {
            context.registerBean("workerSessionServer", WorkerSessionServer.class, () -> server);
            context.registerBean(
                ProbeStoppedAtShutdown.class,
                () -> stopping,
                definition -> definition.setDependsOn("workerSessionServer"));
            context.refresh();
          }

          assertThatThrownBy(() -> probe.await(BOUND)).isInstanceOf(ProbeCancelledException.class);
          assertThat(
                  withoutTarget(
                      stopping.commandSentAtShutdown().get(BOUND.toNanos(), TimeUnit.NANOSECONDS)))
              .isEqualTo(cancel(request, ProbeCancelReason.PROBE_CANCEL_REASON_SERVER_SHUTDOWN));
        }
      }
    }
  }

  /**
   * Stops a probe as a stopping scheduler does. The context destroys this bean before the server
   * that it depends on, so the probe stops while the worker's session is still open.
   */
  private record ProbeStoppedAtShutdown(
      BoundedTask<?> probe,
      LoopbackProbeWorker worker,
      CompletableFuture<CancelProbeCommand> commandSentAtShutdown)
      implements AutoCloseable {

    private ProbeStoppedAtShutdown(BoundedTask<?> probe, LoopbackProbeWorker worker) {
      this(probe, worker, new CompletableFuture<>());
    }

    @Override
    public void close() throws InterruptedException {
      probe.interrupt();
      commandSentAtShutdown.complete(worker.nextResponse().getCancelProbe());
    }
  }

  private static WorkerSessionServer server(WorkerSessionServerConfiguration configuration) {
    return WorkerSessionServer.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(configuration)
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

  private Path sourceRoot() throws IOException {
    return Files.createDirectories(tempDir.resolve("movies"));
  }

  private ProbeExecutionRequest request(ProbePriority priority) throws IOException {
    return ProbeExecutionRequest.builder()
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .sourcePath(Files.createFile(sourceRoot().resolve("film.mkv")))
        .priority(priority)
        .build();
  }

  // The worker's boot identity is random, so the command is compared without its target.
  private static CancelProbeCommand cancel(
      ProbeExecutionRequest request, ProbeCancelReason reason) {
    return CancelProbeCommand.newBuilder()
        .setProbeAttemptId(toProto(request.attemptId()))
        .setReason(reason)
        .build();
  }

  private static CancelProbeCommand withoutTarget(CancelProbeCommand command) {
    return command.toBuilder().clearTarget().build();
  }
}
