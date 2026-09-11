package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsIdentity;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.protocol.WorkerIdentityMetadata;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("IntegrationTest")
@DisplayName("Loopback Worker Session Integration Tests")
class LoopbackWorkerSessionIT {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName("Should release started listeners when another listener fails to bind")
  void shouldReleaseStartedListenersWhenAnotherListenerFailsToBind() throws Exception {
    try (var occupied = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      var listeners =
          WorkerSessionListeners.builder()
              .loopbackPort(OptionalInt.of(occupied.getLocalPort()))
              .mutualTls(Optional.of(serverConfigurationBuilder().build()))
              .build();
      try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
        assertThatThrownBy(server::start).isInstanceOf(IOException.class);
        assertThatThrownBy(server::port).isInstanceOf(IllegalStateException.class);
        occupied.close();

        server.start();

        assertThat(server.port()).isPositive();
        assertThat(server.loopbackPort()).isPositive();
      }
    }
  }

  @Test
  @DisplayName("Should accept a plaintext worker when connecting through loopback")
  void shouldAcceptPlaintextWorkerWhenConnectingThroughLoopback() throws Exception {
    var listeners = WorkerSessionListeners.builder().loopbackPort(OptionalInt.of(0)).build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();
      var channel = plaintextChannel(server.loopbackPort());
      try {
        var accepted = register(channel).get(5, TimeUnit.SECONDS);

        assertThat(server.loopbackPort()).isPositive();
        assertThat(accepted.hasSessionAccepted()).isTrue();
        assertThat(server.availableSlots(SOURCE_ID)).isEqualTo(1);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should replace the old session and abandon its jobs when an identity changes transport")
  void shouldReplaceOldSessionAndAbandonItsJobsWhenIdentityChangesTransport(boolean firstPlaintext)
      throws Exception {
    var listeners =
        WorkerSessionListeners.builder()
            .loopbackPort(OptionalInt.of(0))
            .mutualTls(Optional.of(serverConfigurationBuilder().build()))
            .build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();
      var plaintext = plaintextChannel(server.loopbackPort());
      var encrypted = tlsChannel(server.port());
      try {
        var firstChannel = firstPlaintext ? plaintext : encrypted;
        var replacementChannel = firstPlaintext ? encrypted : plaintext;
        var disconnected = new CompletableFuture<Void>();
        var first = register(firstChannel, disconnected).get(5, TimeUnit.SECONDS);
        var streamId = UUID.randomUUID();
        var job =
            VariantJob.newBuilder()
                .setStreamSessionId(toProto(streamId))
                .setJobId(toProto(UUID.randomUUID()))
                .setJobAttemptId(toProto(UUID.randomUUID()))
                .setSource(MediaSourceRef.newBuilder().setSourceNamespaceId(toProto(SOURCE_ID)))
                .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
                .build();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(server.isRunning(streamId, "720p")).isTrue();

        var replacement = register(replacementChannel).get(5, TimeUnit.SECONDS);

        assertThat(replacement.getSessionAccepted().getWorkerSessionId())
            .isNotEqualTo(first.getSessionAccepted().getWorkerSessionId());
        assertThatThrownBy(() -> disconnected.get(5, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.ABORTED));
        assertThat(server.isRunning(streamId, "720p")).isFalse();
        assertThat(server.availableSlots(SOURCE_ID)).isEqualTo(1);
        firstChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        assertThat(server.dispatch(job)).isTrue();
      } finally {
        plaintext.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        encrypted.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @DisplayName("Should be unreachable through non-loopback addresses when plaintext is enabled")
  void shouldBeUnreachableThroughNonLoopbackAddressesWhenPlaintextIsEnabled() throws Exception {
    var addresses =
        NetworkInterface.networkInterfaces()
            .flatMap(NetworkInterface::inetAddresses)
            .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
            .toList();
    assertThat(addresses).as("This network test needs a non-loopback interface").isNotEmpty();
    var listeners = WorkerSessionListeners.builder().loopbackPort(OptionalInt.of(0)).build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();
      for (var address : addresses) {
        try (var socket = new Socket()) {
          var destination = new InetSocketAddress(address, server.loopbackPort());
          assertThatThrownBy(() -> socket.connect(destination, 1000))
              .isInstanceOf(ConnectException.class);
        }
      }
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"not-a-worker-id"})
  @DisplayName("Should reject missing or malformed identity when connecting through loopback")
  void shouldRejectMissingOrMalformedIdentityWhenConnectingThroughLoopback(String claimedIdentity)
      throws Exception {
    var listeners = WorkerSessionListeners.builder().loopbackPort(OptionalInt.of(0)).build();
    try (var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();
      var channel = plaintextChannel(server.loopbackPort(), Optional.ofNullable(claimedIdentity));
      try {
        var response = register(channel);

        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(server.availableSlots(SOURCE_ID)).isZero();
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private ManagedChannel plaintextChannel(int port) {
    return plaintextChannel(port, Optional.of(WORKER_ID.toString()));
  }

  private ManagedChannel plaintextChannel(int port, Optional<String> claimedIdentity) {
    var headers = new Metadata();
    claimedIdentity.ifPresent(value -> headers.put(WorkerIdentityMetadata.WORKER_ID, value));
    return NettyChannelBuilder.forAddress("127.0.0.1", port)
        .usePlaintext()
        .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
        .build();
  }

  private CompletableFuture<EstablishWorkerSessionResponse> register(ManagedChannel channel) {
    return register(channel, new CompletableFuture<>());
  }

  private ManagedChannel tlsChannel(int port) throws Exception {
    var identity = tlsIdentity("worker-cert.pem", "worker-key.fixture");
    var sslContext =
        GrpcSslContexts.forClient()
            .keyManager(identity.certificate().toFile(), identity.privateKey().toFile())
            .trustManager(identity.trustBundle().toFile())
            .build();
    var headers = new Metadata();
    headers.put(WorkerIdentityMetadata.WORKER_ID, UUID.randomUUID().toString());
    return NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(sslContext)
        .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
        .build();
  }

  private CompletableFuture<EstablishWorkerSessionResponse> register(
      ManagedChannel channel, CompletableFuture<Void> disconnected) {
    var response = new CompletableFuture<EstablishWorkerSessionResponse>();
    var requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(
                new StreamObserver<>() {
                  @Override
                  public void onNext(EstablishWorkerSessionResponse value) {
                    response.complete(value);
                  }

                  @Override
                  public void onError(Throwable failure) {
                    response.completeExceptionally(failure);
                    disconnected.completeExceptionally(failure);
                  }

                  @Override
                  public void onCompleted() {
                    disconnected.complete(null);
                  }
                });
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(
                WorkerRegistration.newBuilder()
                    .setWorker(
                        WorkerIdentity.newBuilder()
                            .setWorkerId(toProto(WORKER_ID))
                            .setBootId(toProto(UUID.randomUUID())))
                    .setCapabilities(
                        WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(SOURCE_ID)))
                    .setAvailableSlots(1))
            .build());
    return response;
  }
}
