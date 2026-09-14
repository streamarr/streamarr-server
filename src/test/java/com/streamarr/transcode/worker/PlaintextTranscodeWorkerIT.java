package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionListeners;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
@DisplayName("Plaintext Transcode Worker Integration Tests")
class PlaintextTranscodeWorkerIT {

  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should reject a hostname when any resolved destination is outside localhost")
  void shouldRejectAHostnameWhenAnyResolvedDestinationIsOutsideLocalhost(boolean localFirst)
      throws Exception {
    var local = InetAddress.getByName("127.0.0.1");
    var remote = InetAddress.getByName("192.0.2.1");
    var answers =
        localFirst ? new InetAddress[] {local, remote} : new InetAddress[] {remote, local};

    try (var worker = plaintextWorker();
        var dns = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
      dns.when(() -> InetAddress.getAllByName("mixed.invalid")).thenReturn(answers);

      assertThatThrownBy(() -> worker.start("mixed.invalid", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("localhost destination");
    }
  }

  @Test
  @DisplayName("Should pin the validated IPv4 destination when a hostname resolves to localhost")
  void shouldPinTheValidatedIpv4DestinationWhenAHostnameResolvesToLocalhost() throws Exception {
    var local = InetAddress.getByName("127.0.0.1");
    var ipv6 = InetAddress.getByName("::1");
    var listeners = WorkerSessionListeners.builder().localhostPort(OptionalInt.of(0)).build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore());
        var worker = plaintextWorker()) {
      server.start();
      try (var dns = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
        dns.when(() -> InetAddress.getAllByName("pinned.invalid"))
            .thenReturn(new InetAddress[] {ipv6, local})
            .thenThrow(new UnknownHostException("DNS changed after validation"));

        worker.start("pinned.invalid", server.localhostPort());

        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
      }
    }
  }

  private TranscodeWorker plaintextWorker() throws Exception {
    var configuration =
        workerConfigurationBuilder()
            .plaintext(true)
            .tlsIdentity(Optional.empty())
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir))
            .segmentBasePath(tempDir.resolve("segments"))
            .build();
    return new TranscodeWorker(configuration, remuxEngine(new FakeFfmpegProcessManager()));
  }

  @Test
  @DisplayName("Should reject plaintext when the control plane is outside localhost")
  void shouldRejectPlaintextWhenTheControlPlaneIsOutsideLocalhost() throws Exception {
    var destination =
        NetworkInterface.networkInterfaces()
            .flatMap(NetworkInterface::inetAddresses)
            .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
            .findFirst()
            .orElseThrow();
    var service =
        new TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase() {
          @Override
          public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
              StreamObserver<EstablishWorkerSessionResponse> responses) {
            return new StreamObserver<>() {
              @Override
              public void onNext(EstablishWorkerSessionRequest request) {
                if (request.hasRegistration()) {
                  responses.onNext(
                      EstablishWorkerSessionResponse.newBuilder()
                          .setSessionAccepted(
                              WorkerSessionAccepted.newBuilder()
                                  .setWorkerSessionId(toProto(UUID.randomUUID())))
                          .build());
                }
              }

              @Override
              public void onError(Throwable failure) {
                // Client cancellation needs no response in this test service.
              }

              @Override
              public void onCompleted() {
                responses.onCompleted();
              }
            };
          }
        };
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress(destination, 0))
            .addService(service)
            .build()
            .start();
    var configuration =
        workerConfigurationBuilder()
            .plaintext(true)
            .tlsIdentity(Optional.empty())
            .availableSlots(1)
            .sourceNamespaces(Map.of(UUID.randomUUID(), tempDir))
            .segmentBasePath(tempDir.resolve("segments"))
            .build();
    try (var worker =
        new TranscodeWorker(configuration, remuxEngine(new FakeFfmpegProcessManager()))) {
      var host = destination.getHostAddress();
      var port = server.getPort();
      assertThatThrownBy(() -> worker.start(host, port))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "localhost"})
  @DisplayName("Should connect without certificates when plaintext is explicitly enabled")
  void shouldConnectWithoutCertificatesWhenPlaintextIsExplicitlyEnabled(String host)
      throws Exception {
    var sourceId = UUID.randomUUID();
    var workerId = UUID.randomUUID();
    var listeners = WorkerSessionListeners.builder().localhostPort(OptionalInt.of(0)).build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();
      var settings =
          TranscodeWorkerSettings.fromEnvironment(
              Map.of(
                  "TRANSCODE_WORKER_PLAINTEXT", "true",
                  "TRANSCODE_WORKER_ID", workerId.toString(),
                  "TRANSCODE_WORKER_CONTROL_PLANE_HOST", host,
                  "TRANSCODE_WORKER_CONTROL_PLANE_PORT", Integer.toString(server.localhostPort()),
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
