package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.ByteString;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.RenditionSpec;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerCapabilities;
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
import java.util.List;
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
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

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
                    .setJobAttemptCompleted(
                        JobAttemptCompleted.newBuilder().setJobAttemptId(uuid(UUID.randomUUID())))
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

  @Test
  @DisplayName("Should decline dispatch when the worker cannot access the source namespace")
  void shouldDeclineDispatchWhenWorkerCannotAccessSourceNamespace() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker =
          connect(
              channel,
              workerIdentity(AUTHENTICATED_WORKER_ID, UUID.randomUUID()),
              UUID.randomUUID())) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();

        assertThat(server.dispatch(renditionJob())).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should bound active rendition ownership by advertised worker capacity")
  void shouldBoundActiveRenditionOwnershipByAdvertisedWorkerCapacity() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        assertThat(server.availableSlots()).isEqualTo(1);
        var first = renditionJob();
        var second = renditionJob();
        assertThat(server.dispatch(first)).isTrue();
        assertThat(worker.nextResponse().getStartRendition().getJob()).isEqualTo(first);
        assertThat(server.availableSlots()).isZero();

        assertThat(server.dispatch(second)).isFalse();

        worker.send(
            WorkerSessionRequest.newBuilder()
                .setJobAttemptCompleted(
                    JobAttemptCompleted.newBuilder().setJobAttemptId(first.getJobAttemptId()))
                .build());
        await().atMost(5, TimeUnit.SECONDS).until(() -> server.availableSlots() == 1);
        assertThat(server.dispatch(second)).isTrue();
        assertThat(worker.nextResponse().getStartRendition().getJob()).isEqualTo(second);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject a segment uploaded by a replaced worker connection")
  void shouldRejectSegmentUploadedByReplacedWorkerConnection() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var firstIdentity = workerIdentity(UUID.randomUUID());
        var first = connect(channel, firstIdentity);
        var firstSession = first.nextResponse().getSessionAccepted();
        var job = renditionJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(first.nextResponse().getStartRendition().getJob()).isEqualTo(job);

        var replacement = connect(channel, workerIdentity(UUID.randomUUID()));
        assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();
        assertThatThrownBy(first::awaitClosed)
            .rootCause()
            .matches(throwable -> Status.fromThrowable(throwable).getCode() == Status.Code.ABORTED);
        var segmentData = "stale segment".getBytes();
        var metadata =
            segmentMetadata(firstSession, firstIdentity, job)
                .setContentLengthBytes(segmentData.length)
                .build();

        assertThatThrownBy(() -> upload(channel, metadata, segmentData).get(5, TimeUnit.SECONDS))
            .rootCause()
            .matches(
                throwable ->
                    Status.fromThrowable(throwable).getCode() == Status.Code.PERMISSION_DENIED);
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
        replacement.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should not publish an incomplete segment upload")
  void shouldNotPublishIncompleteSegmentUpload() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var identity = workerIdentity(UUID.randomUUID());
        var worker = connect(channel, identity);
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = renditionJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartRendition().getJob()).isEqualTo(job);
        var segmentData = "partial segment".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(segmentData.length + 1L)
                .build();

        assertThatThrownBy(() -> upload(channel, metadata, segmentData).get(5, TimeUnit.SECONDS))
            .rootCause()
            .matches(
                throwable ->
                    Status.fromThrowable(throwable).getCode() == Status.Code.INVALID_ARGUMENT);
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
        worker.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject malformed segment uploads without publishing bytes")
  void shouldRejectMalformedSegmentUploadsWithoutPublishingBytes() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = renditionJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartRendition().getJob()).isEqualTo(job);
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();

        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder()
                        .setData(ByteString.copyFromUtf8("x"))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(channel, List.of(UploadSegmentRequest.getDefaultInstance())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder()
                        .setMetadata(
                            metadata.toBuilder().setContentLengthBytes(16L * 1024 * 1024 + 1))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder()
                        .setMetadata(
                            metadata.toBuilder()
                                .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder().setMetadata(metadata).build(),
                    UploadSegmentRequest.newBuilder().setMetadata(metadata).build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder().setMetadata(metadata).build(),
                    UploadSegmentRequest.newBuilder()
                        .setData(ByteString.copyFromUtf8("too long"))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject an upload that loses connection ownership before publication")
  void shouldRejectUploadThatLosesConnectionOwnershipBeforePublication() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var identity = workerIdentity(UUID.randomUUID());
        var worker = connect(channel, identity);
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = renditionJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartRendition().getJob()).isEqualTo(job);
        var segmentData = ByteString.copyFromUtf8("in flight");
        var upload = beginSegmentUpload(channel);
        upload
            .requests()
            .onNext(
                UploadSegmentRequest.newBuilder()
                    .setMetadata(
                        segmentMetadata(workerSession, identity, job)
                            .setContentLengthBytes(segmentData.size()))
                    .build());
        upload.requests().onNext(UploadSegmentRequest.newBuilder().setData(segmentData).build());

        var replacement = connect(channel, workerIdentity(UUID.randomUUID()));
        assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();
        assertThatThrownBy(worker::awaitClosed)
            .rootCause()
            .matches(throwable -> Status.fromThrowable(throwable).getCode() == Status.Code.ABORTED);
        upload.requests().onCompleted();

        assertUploadRejected(upload.response(), Status.Code.PERMISSION_DENIED);
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
        replacement.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  private WorkerSessionServer server() throws URISyntaxException {
    return server(new FakeSegmentStore());
  }

  private WorkerSessionServer server(FakeSegmentStore segmentStore) throws URISyntaxException {
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
    return new WorkerSessionServer(configuration, segmentStore);
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
    return connect(channel, workerIdentity(workerId, UUID.randomUUID()));
  }

  private TestWorkerConnection connect(ManagedChannel channel, WorkerIdentity worker) {
    return connect(channel, worker, SOURCE_NAMESPACE_ID);
  }

  private TestWorkerConnection connect(
      ManagedChannel channel, WorkerIdentity worker, UUID sourceNamespaceId) {
    var responses = new LinkedBlockingQueue<WorkerSessionResponse>();
    var closed = new CompletableFuture<Void>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .workerSession(new QueuedResponseObserver(responses, closed));
    requestObserver.onNext(registration(worker, sourceNamespaceId));
    return new TestWorkerConnection(requestObserver, responses, closed);
  }

  private RenditionJob renditionJob() {
    return RenditionJob.newBuilder()
        .setStreamSessionId(uuid(UUID.randomUUID()))
        .setJobId(uuid(UUID.randomUUID()))
        .setJobAttemptId(uuid(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(uuid(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setRendition(RenditionSpec.newBuilder().setRenditionName("720p"))
        .build();
  }

  private WorkerSessionRequest registration(UUID workerId) {
    return registration(workerIdentity(workerId, UUID.randomUUID()));
  }

  private WorkerSessionRequest registration(WorkerIdentity worker) {
    return registration(worker, SOURCE_NAMESPACE_ID);
  }

  private WorkerSessionRequest registration(WorkerIdentity worker, UUID sourceNamespaceId) {
    return WorkerSessionRequest.newBuilder()
        .setRegistration(
            WorkerRegistration.newBuilder()
                .setWorker(worker)
                .setCapabilities(
                    WorkerCapabilities.newBuilder().addSourceNamespaceIds(uuid(sourceNamespaceId)))
                .setAvailableSlots(1))
        .build();
  }

  private WorkerIdentity workerIdentity(UUID bootId) {
    return workerIdentity(AUTHENTICATED_WORKER_ID, bootId);
  }

  private WorkerIdentity workerIdentity(UUID workerId, UUID bootId) {
    return WorkerIdentity.newBuilder().setWorkerId(uuid(workerId)).setBootId(uuid(bootId)).build();
  }

  private SegmentUploadMetadata.Builder segmentMetadata(
      com.streamarr.transcode.v1.WorkerSessionAccepted workerSession,
      WorkerIdentity worker,
      RenditionJob job) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(workerSession.getWorkerSessionId())
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setRenditionName(job.getRendition().getRenditionName())
        .setSegmentName("segment0.ts")
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP2T);
  }

  private CompletableFuture<UploadSegmentResponse> upload(
      ManagedChannel channel, SegmentUploadMetadata metadata, byte[] data) {
    return uploadRequests(
        channel,
        List.of(
            UploadSegmentRequest.newBuilder().setMetadata(metadata).build(),
            UploadSegmentRequest.newBuilder().setData(ByteString.copyFrom(data)).build()));
  }

  private CompletableFuture<UploadSegmentResponse> uploadRequests(
      ManagedChannel channel, List<UploadSegmentRequest> requests) {
    var upload = beginSegmentUpload(channel);
    requests.forEach(upload.requests()::onNext);
    upload.requests().onCompleted();
    return upload.response();
  }

  private SegmentUploadAttempt beginSegmentUpload(ManagedChannel channel) {
    var response = new CompletableFuture<UploadSegmentResponse>();
    var requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .uploadSegment(new UploadResponseObserver(response));
    return new SegmentUploadAttempt(requests, response);
  }

  private void assertUploadRejected(
      CompletableFuture<UploadSegmentResponse> response, Status.Code expectedStatus) {
    assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
        .rootCause()
        .matches(throwable -> Status.fromThrowable(throwable).getCode() == expectedStatus);
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private UUID uuid(Uuid value) {
    return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
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

  private record UploadResponseObserver(CompletableFuture<UploadSegmentResponse> response)
      implements StreamObserver<UploadSegmentResponse> {

    @Override
    public void onNext(UploadSegmentResponse value) {
      response.complete(value);
    }

    @Override
    public void onError(Throwable throwable) {
      response.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      if (!response.isDone()) {
        response.completeExceptionally(new IllegalStateException("Segment upload closed"));
      }
    }
  }

  private record SegmentUploadAttempt(
      StreamObserver<UploadSegmentRequest> requests,
      CompletableFuture<UploadSegmentResponse> response) {}

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

    private void send(WorkerSessionRequest request) {
      requests.onNext(request);
    }

    @Override
    public void close() {
      requests.onCompleted();
    }
  }
}
