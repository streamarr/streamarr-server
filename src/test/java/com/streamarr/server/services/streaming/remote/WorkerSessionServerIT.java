package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.ByteString;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.ProducerEnd;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.BlockingSegmentStore;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
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
  @DisplayName("Should enforce the worker session server lifecycle")
  void shouldEnforceWorkerSessionServerLifecycle() throws Exception {
    try (var server = server()) {
      assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse();
      assertThatThrownBy(server::port)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is not started");
      server.close();

      server.start();

      assertThatThrownBy(server::start)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is already started");
    }
  }

  @Test
  @DisplayName("Should reject invalid worker session server configuration")
  void shouldRejectInvalidWorkerSessionServerConfiguration() {
    var negativePort =
        WorkerSessionServerConfiguration.builder().port(-1).trustDomain("streamarr.test");
    var excessivePort =
        WorkerSessionServerConfiguration.builder().port(65_536).trustDomain("streamarr.test");
    var missingTrustDomain = WorkerSessionServerConfiguration.builder().port(0);
    var blankTrustDomain = WorkerSessionServerConfiguration.builder().port(0).trustDomain(" ");

    assertThatThrownBy(negativePort::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker session port must be between 0 and 65535");
    assertThatThrownBy(excessivePort::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker session port must be between 0 and 65535");
    assertThatThrownBy(missingTrustDomain::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker trust domain is required");
    assertThatThrownBy(blankTrustDomain::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker trust domain is required");
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
                EstablishWorkerSessionRequest.newBuilder()
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
  @DisplayName("Should dispatch a variant job to a registered worker")
  void shouldDispatchVariantJobToRegisteredWorker() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var job = variantJob();

        assertThat(server.dispatch(job)).isTrue();

        var command = worker.nextResponse().getStartVariant();
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

        assertThat(server.dispatch(variantJob())).isFalse();
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
        var job = variantJob();

        assertThat(server.dispatch(job)).isTrue();
        assertThat(replacement.nextResponse().getStartVariant().getJob()).isEqualTo(job);
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

      assertThat(server.dispatch(variantJob())).isFalse();
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
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, Path.of("/media"));
        assertThat(executor.isHealthy()).isFalse();
        assertThat(executor.availableSlots()).isZero();

        assertThat(server.dispatch(variantJob())).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should bound active variant ownership by advertised worker capacity")
  void shouldBoundActiveVariantOwnershipByAdvertisedWorkerCapacity() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
        var first = variantJob();
        var second = variantJob();
        assertThat(server.dispatch(first)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(first);
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isZero();

        assertThat(server.dispatch(second)).isFalse();

        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptCompleted(
                    JobAttemptCompleted.newBuilder().setJobAttemptId(first.getJobAttemptId()))
                .build());
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> server.availableSlots(SOURCE_NAMESPACE_ID) == 1);
        assertThat(server.dispatch(second)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(second);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject worker operations outside connection-owned variant state")
  void shouldRejectWorkerOperationsOutsideConnectionOwnedVariantState() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        assertThat(server.stopVariant(UUID.randomUUID())).isFalse();
        assertThat(server.dispatch(variantJob().toBuilder().clearSource().build())).isFalse();
        assertThat(server.isRunning(UUID.randomUUID())).isFalse();

        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        assertThat(server.isRunning(uuid(job.getStreamSessionId()), "missing")).isFalse();
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();
        var unownedMetadata =
            List.of(
                metadata.toBuilder().setWorkerSessionId(uuid(UUID.randomUUID())).build(),
                metadata.toBuilder().setWorker(workerIdentity(UUID.randomUUID())).build(),
                metadata.toBuilder().setJobAttemptId(uuid(UUID.randomUUID())).build(),
                metadata.toBuilder().setStreamSessionId(uuid(UUID.randomUUID())).build(),
                metadata.toBuilder().setJobId(uuid(UUID.randomUUID())).build(),
                metadata.toBuilder().setVariantLabel("other").build());

        unownedMetadata.forEach(
            unowned ->
                assertUploadRejected(
                    upload(channel, unowned, new byte[] {1}), Status.Code.PERMISSION_DENIED));
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
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
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(first.nextResponse().getStartVariant().getJob()).isEqualTo(job);

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
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
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
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();

        assertUploadRejected(uploadRequests(channel, List.of()), Status.Code.INVALID_ARGUMENT);
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
  @DisplayName("Should reject an oversized segment upload frame at the transport boundary")
  void shouldRejectOversizedSegmentUploadFrameAtTransportBoundary() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var segmentData = new byte[128 * 1024];
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(segmentData.length)
                .build();

        assertUploadRejected(
            upload(channel, metadata, segmentData), Status.Code.RESOURCE_EXHAUSTED);
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();

        var validSegment = "complete segment".getBytes();
        var accepted =
            upload(
                    channel,
                    metadata.toBuilder().setContentLengthBytes(validSegment.length).build(),
                    validSegment)
                .get(5, TimeUnit.SECONDS);
        assertThat(accepted.getAcceptedLengthBytes()).isEqualTo(validSegment.length);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should reject unsafe segment metadata without publishing bytes")
  void shouldRejectUnsafeSegmentMetadataWithoutPublishingBytes() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();
        var invalidMetadata =
            List.of(
                metadata.toBuilder().setContentLengthBytes(0).build(),
                metadata.toBuilder().setContentLengthBytes(-1).build(),
                metadata.toBuilder().setContentLengthBytes(16L * 1024 * 1024 + 1).build(),
                metadata.toBuilder().setSegmentName(" ").build(),
                metadata.toBuilder().setSegmentName("../segment0.ts").build(),
                metadata.toBuilder().setSegmentName("nested/segment0.ts").build(),
                metadata.toBuilder().setSegmentName("nested\\segment0.ts").build(),
                metadata.toBuilder()
                    .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_UNSPECIFIED)
                    .build(),
                metadata.toBuilder().setContentTypeValue(999).build());

        invalidMetadata.forEach(
            unsafe ->
                assertUploadRejectedAsInvalidMetadata(upload(channel, unsafe, new byte[] {1})));
        assertThat(server.stopVariant(uuid(job.getJobAttemptId()))).isTrue();
        assertThat(worker.nextResponse().hasStopVariant()).isTrue();

        for (var unsafeName : List.of(" ", "..", "720/p", "720\\p")) {
          var unsafeJob =
              variantJob().toBuilder()
                  .setVariant(VariantSpec.newBuilder().setVariantLabel(unsafeName))
                  .build();
          assertThat(server.dispatch(unsafeJob)).isTrue();
          assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(unsafeJob);
          var unsafe =
              segmentMetadata(workerSession, identity, unsafeJob).setContentLengthBytes(1).build();

          assertUploadRejectedAsInvalidMetadata(upload(channel, unsafe, new byte[] {1}));
          assertThat(server.stopVariant(uuid(unsafeJob.getJobAttemptId()))).isTrue();
          assertThat(worker.nextResponse().hasStopVariant()).isTrue();
        }
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should report storage failure without accepting a segment")
  void shouldReportStorageFailureWithoutAcceptingSegment() throws Exception {
    try (var server = server(new FailingSegmentStore())) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var data = "segment".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(data.length)
                .build();

        assertUploadRejected(upload(channel, metadata, data), Status.Code.INTERNAL);
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
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
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

  @Test
  @DisplayName("Should reject an upload that loses ownership during publication")
  void shouldRejectUploadThatLosesOwnershipDuringPublication() throws Exception {
    var segmentStore = new BlockingSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());
      var identity = workerIdentity(UUID.randomUUID());

      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var data = "segment".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(data.length)
                .build();
        var upload = upload(channel, metadata, data);
        assertThat(segmentStore.awaitPreparation(Duration.ofSeconds(5))).isTrue();

        assertThat(server.stopVariant(uuid(job.getJobAttemptId()))).isTrue();
        assertThat(worker.nextResponse().hasStopVariant()).isTrue();
        segmentStore.continuePreparation();

        assertUploadRejected(upload, Status.Code.PERMISSION_DENIED);
        assertThat(segmentStore.segmentExists(uuid(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
      } finally {
        segmentStore.continuePreparation();
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should expose live worker connections as execution targets for their namespace")
  void shouldExposeLiveWorkerConnectionsAsExecutionTargetsForTheirNamespace() throws Exception {
    try (var server = server()) {
      server.start();
      assertThat(server.eligibleWorkers(SOURCE_NAMESPACE_ID)).isEmpty();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var expectedTarget =
            new ExecutionTargetId(uuid(workerSession.getWorkerSessionId()).toString());

        assertThat(server.eligibleWorkers(SOURCE_NAMESPACE_ID)).containsExactly(expectedTarget);
        assertThat(server.eligibleWorkers(UUID.randomUUID())).isEmpty();

        var job = variantJob();
        assertThat(server.dispatchTo(new ExecutionTargetId(UUID.randomUUID().toString()), job))
            .isFalse();
        assertThat(server.dispatchTo(expectedTarget, job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should dispatch jobs carrying the handle's attempt identity end to end")
  void shouldDispatchJobsCarryingTheHandlesAttemptIdentityEndToEnd() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, Path.of("/media"));
        var request =
            TranscodeRequest.builder()
                .sessionId(UUID.randomUUID())
                .sourcePath(Path.of("/media/movie.mkv"))
                .targetSegmentDuration(6)
                .framerate(23.976)
                .transcodeDecision(StreamSessionFixture.remuxMpegtsDecision())
                .width(1920)
                .height(1080)
                .bitrate(5_000_000)
                .variantLabel("720p")
                .build();

        var handle = executor.start(request);

        // The attempt identity is minted once, upstream: the dispatched job, the returned handle,
        // and any later failure evidence all name the same attempt.
        var job = worker.nextResponse().getStartVariant().getJob();
        assertThat(uuid(job.getJobAttemptId())).isEqualTo(handle.attemptId());
        assertThat(handle.attemptId()).isEqualTo(request.attemptId());
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should retain attempt-scoped evidence and reject stale uploads after a failure")
  void shouldRetainAttemptScopedEvidenceAndRejectStaleUploadsAfterFailure() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        var streamSessionId = uuid(job.getStreamSessionId());
        var attemptId = uuid(job.getJobAttemptId());
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);

        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptFailed(
                    JobAttemptFailed.newBuilder()
                        .setJobAttemptId(job.getJobAttemptId())
                        .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
                .build());
        await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId, "720p"));

        assertThat(server.consumeEnd(streamSessionId, "wrong-variant", attemptId)).isEmpty();
        assertThat(server.consumeEnd(streamSessionId, "720p", UUID.randomUUID())).isEmpty();
        var evidence = server.consumeEnd(streamSessionId, "720p", attemptId);
        assertThat(evidence).isPresent();
        assertThat(evidence.get().kind()).isEqualTo(ProducerEnd.EndKind.FAILED);
        assertThat(evidence.get().detail()).contains("TRANSCODE_FAILED");
        assertThat(server.consumeEnd(streamSessionId, "720p", attemptId)).isEmpty();

        // The failed attempt no longer authorizes uploads: its data plane is fenced too.
        var stale = "stale".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(stale.length)
                .build();
        assertUploadRejected(upload(channel, metadata, stale), Status.Code.PERMISSION_DENIED);
        assertThat(segmentStore.segmentExists(streamSessionId, "720p/segment0.ts")).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should retain completed evidence but none for a server-initiated planned stop")
  void shouldRetainCompletedEvidenceButNoneForServerInitiatedPlannedStop() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var completedJob = variantJob();
        var completedSession = uuid(completedJob.getStreamSessionId());
        assertThat(server.dispatch(completedJob)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(completedJob);
        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptCompleted(
                    JobAttemptCompleted.newBuilder()
                        .setJobAttemptId(completedJob.getJobAttemptId()))
                .build());
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.isRunning(completedSession, "720p"));
        var completedEnd =
            server.consumeEnd(completedSession, "720p", uuid(completedJob.getJobAttemptId()));
        assertThat(completedEnd).isPresent();
        assertThat(completedEnd.get().kind()).isEqualTo(ProducerEnd.EndKind.COMPLETED);

        var stoppedJob = variantJob();
        var stoppedSession = uuid(stoppedJob.getStreamSessionId());
        assertThat(server.dispatch(stoppedJob)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(stoppedJob);
        assertThat(server.stopVariant(stoppedSession, "720p")).isTrue();
        assertThat(worker.nextResponse().hasStopVariant()).isTrue();
        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptStopped(
                    JobAttemptStopped.newBuilder().setJobAttemptId(stoppedJob.getJobAttemptId()))
                .build());
        // The server-side stop already released the attempt, so the worker's confirmation finds
        // nothing to release and no evidence is retained for the planned stop.
        await().pollDelay(Duration.ofMillis(200)).until(() -> true);
        assertThat(server.consumeEnd(stoppedSession, "720p", uuid(stoppedJob.getJobAttemptId())))
            .isEmpty();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should retain disconnected evidence for jobs abandoned by a closing worker")
  void shouldRetainDisconnectedEvidenceForJobsAbandonedByClosingWorker() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var worker = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var job = variantJob();
        var streamSessionId = uuid(job.getStreamSessionId());
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);

        worker.close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId, "720p"));

        var evidence = server.consumeEnd(streamSessionId, "720p", uuid(job.getJobAttemptId()));
        assertThat(evidence).isPresent();
        assertThat(evidence.get().kind()).isEqualTo(ProducerEnd.EndKind.DISCONNECTED);
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

  private CompletableFuture<EstablishWorkerSessionResponse> register(
      ManagedChannel channel, UUID reportedWorkerId) {
    return send(channel, registration(reportedWorkerId));
  }

  private CompletableFuture<EstablishWorkerSessionResponse> send(
      ManagedChannel channel, EstablishWorkerSessionRequest request) {
    var response = new CompletableFuture<EstablishWorkerSessionResponse>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new FirstResponseObserver(response));
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
    var responses = new LinkedBlockingQueue<EstablishWorkerSessionResponse>();
    var closed = new CompletableFuture<Void>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new QueuedResponseObserver(responses, closed));
    requestObserver.onNext(registration(worker, sourceNamespaceId));
    return new TestWorkerConnection(requestObserver, responses, closed);
  }

  private VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(uuid(UUID.randomUUID()))
        .setJobId(uuid(UUID.randomUUID()))
        .setJobAttemptId(uuid(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(uuid(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private EstablishWorkerSessionRequest registration(UUID workerId) {
    return registration(workerIdentity(workerId, UUID.randomUUID()));
  }

  private EstablishWorkerSessionRequest registration(WorkerIdentity worker) {
    return registration(worker, SOURCE_NAMESPACE_ID);
  }

  private EstablishWorkerSessionRequest registration(
      WorkerIdentity worker, UUID sourceNamespaceId) {
    return EstablishWorkerSessionRequest.newBuilder()
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
      WorkerSessionAccepted workerSession, WorkerIdentity worker, VariantJob job) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(workerSession.getWorkerSessionId())
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setVariantLabel(job.getVariant().getVariantLabel())
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

  private void assertUploadRejectedAsInvalidMetadata(
      CompletableFuture<UploadSegmentResponse> response) {
    assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
        .rootCause()
        .matches(
            throwable -> {
              var status = Status.fromThrowable(throwable);
              return status.getCode() == Status.Code.INVALID_ARGUMENT
                  && status.getDescription().equals("Segment metadata is invalid");
            });
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

  private record FirstResponseObserver(CompletableFuture<EstablishWorkerSessionResponse> response)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
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
      BlockingQueue<EstablishWorkerSessionResponse> responses, CompletableFuture<Void> closed)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
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

  private static final class FailingSegmentStore extends FakeSegmentStore {

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      throw new IllegalStateException("Storage unavailable");
    }
  }

  private record TestWorkerConnection(
      StreamObserver<EstablishWorkerSessionRequest> requests,
      BlockingQueue<EstablishWorkerSessionResponse> responses,
      CompletableFuture<Void> closed)
      implements AutoCloseable {

    private EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
      return Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS));
    }

    private void awaitClosed() throws Exception {
      closed.get(5, TimeUnit.SECONDS);
    }

    private void send(EstablishWorkerSessionRequest request) {
      requests.onNext(request);
    }

    @Override
    public void close() {
      requests.onCompleted();
    }
  }
}
