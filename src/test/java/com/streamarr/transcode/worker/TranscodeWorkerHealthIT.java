package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsResource;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Health Integration Tests")
class TranscodeWorkerHealthIT {

  @TempDir Path directory;

  @Test
  @DisplayName("Should become ready when the control plane accepts the worker session")
  void shouldBecomeReadyWhenTheControlPlaneAcceptsTheWorkerSession() throws Exception {
    try (var controlPlane = controlPlane();
        var worker = worker()) {
      controlPlane.start();

      worker.start("localhost", controlPlane.port());

      assertThat(check(worker.healthPort(), "readiness")).isEqualTo(ServingStatus.SERVING);
      assertThat(check(worker.healthPort(), "liveness")).isEqualTo(ServingStatus.SERVING);
    }
  }

  @Test
  @DisplayName("Should remain live but become unready when the control plane disconnects")
  void shouldRemainLiveButBecomeUnreadyWhenTheControlPlaneDisconnects() throws Exception {
    try (var controlPlane = controlPlane();
        var worker = worker()) {
      controlPlane.start();
      worker.start("localhost", controlPlane.port());

      controlPlane.close();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(check(worker.healthPort(), "readiness"))
                      .isEqualTo(ServingStatus.NOT_SERVING));
      assertThat(check(worker.healthPort(), "liveness")).isEqualTo(ServingStatus.SERVING);
    }
  }

  @Test
  @DisplayName("Should remain ready when session dispatch has occupied every execution slot")
  void shouldRemainReadyWhenSessionDispatchHasOccupiedEveryExecutionSlot() throws Exception {
    var media = Files.writeString(directory.resolve("movie.mkv"), "media");
    var processes = new FakeFfmpegProcessManager();
    try (var controlPlane = controlPlane();
        var worker = worker(processes)) {
      controlPlane.start();
      worker.start("localhost", controlPlane.port());
      var executor = new RemoteTranscodeExecutor(controlPlane, SOURCE_NAMESPACE_ID, directory);

      var active = request(media);
      executor.start(active);
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processes.isRunning(active.sessionId(), active.variantLabel()));

      assertThat(executor.availableSlots()).isZero();
      assertThatThrownBy(() -> executor.start(request(media)))
          .isInstanceOf(TranscodeException.class);
      assertThat(check(worker.healthPort(), "readiness")).isEqualTo(ServingStatus.SERVING);
      assertThat(check(worker.healthPort(), "liveness")).isEqualTo(ServingStatus.SERVING);
    }
  }

  @Test
  @DisplayName("Should become ready again when a replacement worker session is accepted")
  void shouldBecomeReadyAgainWhenAReplacementWorkerSessionIsAccepted() throws Exception {
    try (var worker = worker()) {
      try (var first = controlPlane()) {
        first.start();
        worker.start("localhost", first.port());
        first.close();
        await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () ->
                    assertThat(check(worker.healthPort(), "readiness"))
                        .isEqualTo(ServingStatus.NOT_SERVING));
      }

      worker.close();
      try (var replacement = controlPlane()) {
        replacement.start();

        worker.start("localhost", replacement.port());

        assertThat(check(worker.healthPort(), "readiness")).isEqualTo(ServingStatus.SERVING);
        assertThat(check(worker.healthPort(), "liveness")).isEqualTo(ServingStatus.SERVING);
      }
    }
  }

  @Test
  @DisplayName("Should stay unready when registration has not yet been accepted")
  void shouldStayUnreadyWhenRegistrationHasNotYetBeenAccepted() throws Exception {
    try (var controlPlane = new DeferredControlPlane();
        var worker = worker();
        var execution = Executors.newVirtualThreadPerTaskExecutor()) {
      var starting =
          execution.submit(
              () -> {
                worker.start("localhost", controlPlane.port());
                return null;
              });
      controlPlane.awaitRegistration();

      assertThat(check(worker.healthPort(), "liveness")).isEqualTo(ServingStatus.SERVING);
      assertThat(check(worker.healthPort(), "readiness")).isEqualTo(ServingStatus.NOT_SERVING);

      controlPlane.acceptSession();
      starting.get(5, TimeUnit.SECONDS);
      assertThat(check(worker.healthPort(), "readiness")).isEqualTo(ServingStatus.SERVING);
    }
  }

  @Test
  @DisplayName("Should remain live but become unready when the accepted session ends normally")
  void shouldRemainLiveButBecomeUnreadyWhenTheAcceptedSessionEndsNormally() throws Exception {
    try (var controlPlane = new DeferredControlPlane();
        var worker = worker();
        var execution = Executors.newVirtualThreadPerTaskExecutor()) {
      var starting =
          execution.submit(
              () -> {
                worker.start("localhost", controlPlane.port());
                return null;
              });
      controlPlane.awaitRegistration();
      controlPlane.acceptSession();
      starting.get(5, TimeUnit.SECONDS);

      controlPlane.endSession();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(check(worker.healthPort(), "readiness"))
                      .isEqualTo(ServingStatus.NOT_SERVING));
      assertThat(check(worker.healthPort(), "liveness")).isEqualTo(ServingStatus.SERVING);
    }
  }

  private TranscodeRequest request(Path media) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(media)
        .targetSegmentDuration(6)
        .framerate(24)
        .transcodeDecision(defaultSessionBuilder().build().getTranscodeDecision())
        .build();
  }

  private TranscodeWorker worker() throws Exception {
    return worker(new FakeFfmpegProcessManager());
  }

  private TranscodeWorker worker(FakeFfmpegProcessManager processes) throws Exception {
    var configuration =
        workerConfigurationBuilder()
            .healthPort(0)
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, directory))
            .segmentBasePath(directory.resolve("segments"))
            .build();
    return new TranscodeWorker(configuration, remuxEngine(processes));
  }

  private WorkerSessionServer controlPlane() throws Exception {
    return new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
  }

  private ServingStatus check(int port, String service) throws InterruptedException {
    var channel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    try {
      return HealthGrpc.newBlockingStub(channel)
          .withDeadlineAfter(5, TimeUnit.SECONDS)
          .check(HealthCheckRequest.newBuilder().setService(service).build())
          .getStatus();
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static final class DeferredControlPlane
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

    private final CompletableFuture<WorkerRegistration> registered = new CompletableFuture<>();
    private final Server server;
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    private DeferredControlPlane() throws Exception {
      var tls =
          GrpcSslContexts.forServer(
                  tlsResource("server-cert.pem").toFile(),
                  tlsResource("server-key.fixture").toFile())
              .trustManager(tlsResource("ca-cert.pem").toFile())
              .clientAuth(ClientAuth.REQUIRE)
              .build();
      server = NettyServerBuilder.forPort(0).sslContext(tls).addService(this).build().start();
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      responses = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest request) {
          registered.complete(request.getRegistration());
        }

        @Override
        public void onError(Throwable throwable) {
          // Each test controls the server's response stream directly.
        }

        @Override
        public void onCompleted() {
          // Each test controls the server's response stream directly.
        }
      };
    }

    private int port() {
      return server.getPort();
    }

    private void awaitRegistration() throws Exception {
      registered.get(5, TimeUnit.SECONDS);
    }

    private void acceptSession() {
      responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setSessionAccepted(
                  WorkerSessionAccepted.newBuilder().setWorkerSessionId(toProto(UUID.randomUUID())))
              .build());
    }

    private void endSession() {
      responses.onCompleted();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
