package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Worker probe control plane")
class WorkerProbeControlPlaneIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should reply with execution failure when the producer throws an unchecked exception")
  void shouldReplyWithExecutionFailureWhenTheProducerThrowsAnUncheckedException() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var ffprobe =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new IllegalStateException("producer unavailable");
            });
    var request = request("movie.mkv").build();

    try (var controlPlane = new ProbeControlPlane();
        var worker = worker(ffprobe)) {
      worker.start("localhost", controlPlane.port());
      controlPlane.start(controlPlane.identity(), request);

      assertThat(controlPlane.awaitResult(request).getFailure())
          .isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    }
  }

  @ParameterizedTest
  @CsvSource({"1, PROBE_FAILURE_INVALID_MEDIA", "2, PROBE_FAILURE_UNSUPPORTED_VERSION"})
  @DisplayName("Should retain typed probe failures when the worker rejects media or a version")
  void shouldRetainTypedProbeFailuresWhenTheWorkerRejectsMediaOrAVersion(
      int version, ProbeFailure failure) throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var launched = new AtomicInteger();
    var ffprobe =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              launched.incrementAndGet();
              try {
                return new ProcessBuilder(
                        "sh", "-c", "echo '{\"error\":{\"code\":-1094995529}}'; exit 1")
                    .start();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    var request = request("movie.mkv").setProbeVersion(version).build();

    try (var controlPlane = new ProbeControlPlane();
        var worker = worker(ffprobe)) {
      worker.start("localhost", controlPlane.port());
      controlPlane.start(controlPlane.identity(), request);

      var result = controlPlane.awaitResult(request);
      assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
      assertThat(result.getProbeVersion()).isEqualTo(version);
      assertThat(result.getFailure()).isEqualTo(failure);
      assertThat(launched.get()).isEqualTo(version == FfprobeExecutor.PROBE_VERSION ? 1 : 0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should ignore a probe start when it targets another worker or boot")
  void shouldIgnoreAProbeStartWhenItTargetsAnotherWorkerOrBoot(boolean earlierBoot)
      throws Exception {
    var source = Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var launched = new AtomicInteger();
    var ffprobe =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              launched.incrementAndGet();
              return successfulProcess();
            });
    var valid = request(source.getFileName().toString()).build();

    try (var controlPlane = new ProbeControlPlane()) {
      try (var worker = worker(ffprobe)) {
        worker.start("localhost", controlPlane.port());
        var target = controlPlane.identity();
        controlPlane.start(otherIdentity(target, earlierBoot), request("movie.mkv").build());
        controlPlane.start(target, valid);

        controlPlane.awaitResult(valid);
      }

      assertThat(launched.get()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should preserve a probe when a cancel command targets another worker or boot")
  void shouldPreserveAProbeWhenACancelCommandTargetsAnotherWorkerOrBoot(boolean earlierBoot)
      throws Exception {
    Files.writeString(tempDir.resolve("held.mkv"), "media");
    Files.writeString(tempDir.resolve("barrier.mkv"), "media");
    var held =
        new ProcessBuilder(
                "sh",
                "-c",
                "read line; echo '{\"streams\":[{\"index\":0,\"codec_type\":\"video\"}]}'")
            .start();
    var started = new CompletableFuture<Process>();
    var ffprobe =
        new FfprobeExecutor(
            new ObjectMapper(),
            source -> {
              if (source.getFileName().toString().equals("held.mkv")) {
                started.complete(held);
                return held;
              }

              return successfulProcess();
            });
    var protectedRequest = request("held.mkv").build();
    var barrier = request("barrier.mkv").build();

    try (var controlPlane = new ProbeControlPlane();
        var worker = worker(ffprobe)) {
      worker.start("localhost", controlPlane.port());
      var target = controlPlane.identity();
      controlPlane.start(target, protectedRequest);
      assertThat(started.get(5, TimeUnit.SECONDS)).isSameAs(held);

      controlPlane.cancel(otherIdentity(target, earlierBoot), protectedRequest);
      controlPlane.start(target, barrier);
      assertThat(controlPlane.awaitResult(barrier).hasMedia()).isTrue();

      try (var input = held.getOutputStream()) {
        input.write('\n');
      }

      assertThat(controlPlane.awaitResult(protectedRequest).hasMedia()).isTrue();
    } finally {
      held.destroyForcibly();
      held.onExit().join();
    }
  }

  private WorkerIdentity otherIdentity(WorkerIdentity target, boolean earlierBoot) {
    if (earlierBoot) {
      return target.toBuilder().setBootId(toProto(UUID.randomUUID())).build();
    }

    return target.toBuilder().setWorkerId(toProto(UUID.randomUUID())).build();
  }

  private Process successfulProcess() {
    try {
      return new ProcessBuilder(
              "sh", "-c", "echo '{\"streams\":[{\"index\":0,\"codec_type\":\"video\"}]}'")
          .start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private TranscodeWorker worker(FfprobeExecutor ffprobe) throws Exception {
    var configuration =
        workerConfigurationBuilder()
            .plaintext(true)
            .tlsIdentity(Optional.empty())
            .availableSlots(2)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir))
            .segmentBasePath(tempDir.resolve("segments"))
            .build();
    return new TranscodeWorker(configuration, remuxEngine(new FakeFfmpegProcessManager()), ffprobe);
  }

  private ProbeRequest.Builder request(String key) {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey(key));
  }

  private static final class ProbeControlPlane
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

    private final CompletableFuture<WorkerIdentity> registered = new CompletableFuture<>();
    private final Map<UUID, CompletableFuture<ProbeAttemptResult>> results =
        new ConcurrentHashMap<>();
    private final Server server;
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    private ProbeControlPlane() throws Exception {
      server = NettyServerBuilder.forPort(0).addService(this).build().start();
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      responses = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest value) {
          if (value.hasRegistration()) {
            registered.complete(value.getRegistration().getWorker());
            responses.onNext(
                EstablishWorkerSessionResponse.newBuilder()
                    .setSessionAccepted(
                        WorkerSessionAccepted.newBuilder()
                            .setWorkerSessionId(toProto(UUID.randomUUID())))
                    .build());
            return;
          }

          var result = value.getProbeResult();
          results
              .computeIfAbsent(
                  fromProto(result.getProbeAttemptId()), _ -> new CompletableFuture<>())
              .complete(result);
        }

        @Override
        public void onError(Throwable failure) {
          // Client shutdown is observed through the worker's public close operation.
        }

        @Override
        public void onCompleted() {
          responses.onCompleted();
        }
      };
    }

    private int port() {
      return server.getPort();
    }

    private WorkerIdentity identity() throws Exception {
      return registered.get(5, TimeUnit.SECONDS);
    }

    private void start(WorkerIdentity target, ProbeRequest request) {
      responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartProbe(StartProbeCommand.newBuilder().setTarget(target).setRequest(request))
              .build());
    }

    private void cancel(WorkerIdentity target, ProbeRequest request) {
      responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setCancelProbe(
                  CancelProbeCommand.newBuilder()
                      .setTarget(target)
                      .setProbeAttemptId(request.getProbeAttemptId()))
              .build());
    }

    private ProbeAttemptResult awaitResult(ProbeRequest request) throws Exception {
      return results
          .computeIfAbsent(fromProto(request.getProbeAttemptId()), _ -> new CompletableFuture<>())
          .get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
