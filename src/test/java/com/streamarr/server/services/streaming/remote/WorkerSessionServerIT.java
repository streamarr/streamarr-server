package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerHeartbeat;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionRequest;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
@DisplayName("Worker Session Server Integration Tests")
class WorkerSessionServerIT extends AbstractIntegrationTest {

  private static final UUID AUTHENTICATED_WORKER_ID =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  @Test
  @DisplayName("Should register a worker whose reported identity matches its mTLS identity")
  void shouldRegisterWorkerWhoseReportedIdentityMatchesItsMtlsIdentity() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var response = register(channel, AUTHENTICATED_WORKER_ID).get(5, TimeUnit.SECONDS);

        assertThat(response.hasSessionAccepted()).isTrue();
        assertThat(response.getSessionAccepted().hasWorkerSessionId()).isTrue();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject a reported worker identity that differs from its mTLS identity")
  void shouldRejectReportedWorkerIdentityThatDiffersFromItsMtlsIdentity() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var response = register(channel, UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
            .rootCause()
            .matches(
                throwable ->
                    Status.fromThrowable(throwable).getCode() == Status.Code.PERMISSION_DENIED);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject a worker that presents no client certificate")
  void shouldRejectWorkerThatPresentsNoClientCertificate() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = unauthenticatedChannel(server.port());

      try {
        var response = register(channel, AUTHENTICATED_WORKER_ID);

        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOf(SSLException.class)
            .hasMessageContaining("CERTIFICATE_REQUIRED");
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject a worker session that does not begin with registration")
  void shouldRejectWorkerSessionThatDoesNotBeginWithRegistration() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var response =
            send(
                channel,
                WorkerSessionRequest.newBuilder()
                    .setHeartbeat(WorkerHeartbeat.newBuilder().setAvailableSlots(1))
                    .build());

        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
            .rootCause()
            .matches(
                throwable ->
                    Status.fromThrowable(throwable).getCode() == Status.Code.INVALID_ARGUMENT);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject a trusted certificate outside the worker trust domain")
  void shouldRejectTrustedCertificateOutsideWorkerTrustDomain() throws Exception {
    try (var server = server()) {
      server.start();
      var channel =
          workerChannel(server.port(), "unmapped-worker-cert.pem", "unmapped-worker-key.fixture");

      try {
        var response = register(channel, AUTHENTICATED_WORKER_ID);

        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
            .rootCause()
            .matches(
                throwable ->
                    Status.fromThrowable(throwable).getCode() == Status.Code.UNAUTHENTICATED);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should dispatch a rendition job to a registered worker")
  void shouldDispatchRenditionJobToRegisteredWorker() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var job = renditionJob();

        assertThat(server.dispatch(job)).isTrue();

        var command = worker.nextResponse().getStartRendition();
        assertThat(command.getTarget().getWorkerId()).isEqualTo(uuid(AUTHENTICATED_WORKER_ID));
        assertThat(command.getJob()).isEqualTo(job);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should stop dispatching when the worker connection closes")
  void shouldStopDispatchingWhenWorkerConnectionCloses() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var worker = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();

        worker.close();
        worker.awaitClosed();

        assertThat(server.dispatch(renditionJob())).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should fence a replaced worker connection without removing its replacement")
  void shouldFenceReplacedWorkerConnectionWithoutRemovingItsReplacement() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var first = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(first.nextResponse().hasSessionAccepted()).isTrue();
        var replacement = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();

        assertThatThrownBy(first::awaitClosed)
            .rootCause()
            .matches(throwable -> Status.fromThrowable(throwable).getCode() == Status.Code.ABORTED);
        var job = renditionJob();

        assertThat(server.dispatch(job)).isTrue();
        assertThat(replacement.nextResponse().getStartRendition().getJob()).isEqualTo(job);
        replacement.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should decline dispatch when no worker is connected")
  void shouldDeclineDispatchWhenNoWorkerIsConnected() throws Exception {
    try (var server = server()) {
      server.start();

      assertThat(server.dispatch(renditionJob())).isFalse();
    }
  }

  private WorkerSessionServer server() throws URISyntaxException {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .port(0)
            .trustDomain("streamarr.test")
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("server-cert.pem"))
                    .privateKey(resource("server-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .build();
    return new WorkerSessionServer(configuration);
  }

  private ManagedChannel workerChannel(int port) throws Exception {
    return workerChannel(port, "worker-cert.pem", "worker-key.fixture");
  }

  private ManagedChannel workerChannel(int port, String certificate, String privateKey)
      throws Exception {
    var identity =
        PemTlsIdentity.builder()
            .certificate(resource(certificate))
            .privateKey(resource(privateKey))
            .trustBundle(resource("ca-cert.pem"))
            .build();
    var sslContext =
        GrpcSslContexts.forClient()
            .keyManager(identity.certificate().toFile(), identity.privateKey().toFile())
            .trustManager(identity.trustBundle().toFile())
            .build();
    return NettyChannelBuilder.forAddress("localhost", port).sslContext(sslContext).build();
  }

  private ManagedChannel unauthenticatedChannel(int port) throws Exception {
    var sslContext =
        GrpcSslContexts.forClient().trustManager(resource("ca-cert.pem").toFile()).build();
    return NettyChannelBuilder.forAddress("localhost", port).sslContext(sslContext).build();
  }

  private CompletableFuture<WorkerSessionResponse> register(
      ManagedChannel channel, UUID reportedWorkerId) {
    return send(channel, registration(reportedWorkerId));
  }

  private CompletableFuture<WorkerSessionResponse> send(
      ManagedChannel channel, WorkerSessionRequest request) {
    var response = new CompletableFuture<WorkerSessionResponse>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .workerSession(new FirstResponseObserver(response));
    requestObserver.onNext(request);
    return response;
  }

  private TestWorkerConnection connect(ManagedChannel channel, UUID workerId) {
    var responses = new LinkedBlockingQueue<WorkerSessionResponse>();
    var closed = new CompletableFuture<Void>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .workerSession(new QueuedResponseObserver(responses, closed));
    requestObserver.onNext(registration(workerId));
    return new TestWorkerConnection(requestObserver, responses, closed);
  }

  private RenditionJob renditionJob() {
    return RenditionJob.newBuilder()
        .setStreamSessionId(uuid(UUID.randomUUID()))
        .setJobId(uuid(UUID.randomUUID()))
        .setJobAttemptId(uuid(UUID.randomUUID()))
        .build();
  }

  private WorkerSessionRequest registration(UUID workerId) {
    return WorkerSessionRequest.newBuilder()
        .setRegistration(
            WorkerRegistration.newBuilder()
                .setWorker(
                    WorkerIdentity.newBuilder()
                        .setWorkerId(uuid(workerId))
                        .setBootId(uuid(UUID.randomUUID())))
                .setAvailableSlots(1))
        .build();
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private Path resource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(getClass().getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  private void shutdown(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  private record FirstResponseObserver(CompletableFuture<WorkerSessionResponse> response)
      implements StreamObserver<WorkerSessionResponse> {

    @Override
    public void onNext(WorkerSessionResponse value) {
      response.complete(value);
    }

    @Override
    public void onError(Throwable throwable) {
      response.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      if (!response.isDone()) {
        response.completeExceptionally(new IllegalStateException("Worker session closed"));
      }
    }
  }

  private record QueuedResponseObserver(
      BlockingQueue<WorkerSessionResponse> responses, CompletableFuture<Void> closed)
      implements StreamObserver<WorkerSessionResponse> {

    @Override
    public void onNext(WorkerSessionResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      closed.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      closed.complete(null);
    }
  }

  private record TestWorkerConnection(
      StreamObserver<WorkerSessionRequest> requests,
      BlockingQueue<WorkerSessionResponse> responses,
      CompletableFuture<Void> closed)
      implements AutoCloseable {

    private WorkerSessionResponse nextResponse() throws InterruptedException {
      return Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS));
    }

    private void awaitClosed() throws Exception {
      closed.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      requests.onCompleted();
    }
  }
}
