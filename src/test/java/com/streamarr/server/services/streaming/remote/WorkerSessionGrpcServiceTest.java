package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.RenditionSpec;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Session gRPC Service Tests")
class WorkerSessionGrpcServiceTest {

  @Test
  @DisplayName("Should reject an upload when the global concurrent upload limit is exhausted")
  void shouldRejectUploadWhenGlobalConcurrentUploadLimitIsExhausted() {
    var service =
        new WorkerSessionGrpcService(new LiveWorkerConnectionRegistry(), new FakeSegmentStore());
    var uploads = new ArrayList<StreamObserver<UploadSegmentRequest>>();
    for (var index = 0; index < 32; index++) {
      uploads.add(service.uploadSegment(new RecordingUploadResponseObserver()));
    }
    var rejected = new RecordingUploadResponseObserver();

    var ignored = service.uploadSegment(rejected);

    assertThat(rejected.error()).isNotNull();
    assertThat(Status.fromThrowable(rejected.error()).getCode())
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    ignored.onNext(UploadSegmentRequest.getDefaultInstance());
    ignored.onError(Status.CANCELLED.asRuntimeException());
    ignored.onCompleted();

    uploads.removeFirst().onError(Status.CANCELLED.asRuntimeException());
    var resumed = new RecordingUploadResponseObserver();
    var resumedUpload = service.uploadSegment(resumed);

    assertThat(resumed.error()).isNull();
    resumedUpload.onError(Status.CANCELLED.asRuntimeException());
    resumedUpload.onNext(UploadSegmentRequest.getDefaultInstance());
    resumedUpload.onError(Status.CANCELLED.asRuntimeException());
    uploads.forEach(upload -> upload.onError(Status.CANCELLED.asRuntimeException()));
  }

  @Test
  @DisplayName("Should reject an upload when the global declared byte budget is exhausted")
  void shouldRejectUploadWhenGlobalDeclaredByteBudgetIsExhausted() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var registry = new LiveWorkerConnectionRegistry();
    var workerSessionId =
        registry.register(
            workerId, registration(worker, sourceNamespaceId), new IgnoringResponseObserver());
    var job = renditionJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isTrue();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var metadata = metadata(workerSessionId, worker, job, 16L * 1024 * 1024);
    var uploads = new ArrayList<StreamObserver<UploadSegmentRequest>>();
    for (var index = 0; index < 4; index++) {
      var response = new RecordingUploadResponseObserver();
      var upload = upload(service, workerId, response);
      upload.onNext(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());
      assertThat(response.error()).isNull();
      uploads.add(upload);
    }
    var rejected = new RecordingUploadResponseObserver();
    var excess = upload(service, workerId, rejected);

    excess.onNext(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());

    assertThat(rejected.error()).isNotNull();
    assertThat(Status.fromThrowable(rejected.error()).getCode())
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);

    uploads.removeFirst().onError(Status.CANCELLED.asRuntimeException());
    var resumed = new RecordingUploadResponseObserver();
    var resumedUpload = upload(service, workerId, resumed);
    resumedUpload.onNext(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());

    assertThat(resumed.error()).isNull();
    resumedUpload.onError(Status.CANCELLED.asRuntimeException());
    uploads.forEach(upload -> upload.onError(Status.CANCELLED.asRuntimeException()));
  }

  private static StreamObserver<UploadSegmentRequest> upload(
      WorkerSessionGrpcService service,
      UUID workerId,
      RecordingUploadResponseObserver responseObserver)
      throws Exception {
    return Context.current()
        .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, workerId)
        .call(() -> service.uploadSegment(responseObserver));
  }

  private static WorkerIdentity worker(UUID workerId) {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(workerId))
        .setBootId(toProto(UUID.randomUUID()))
        .build();
  }

  private static WorkerRegistration registration(WorkerIdentity worker, UUID sourceNamespaceId) {
    return WorkerRegistration.newBuilder()
        .setWorker(worker)
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(sourceNamespaceId)))
        .setAvailableSlots(1)
        .build();
  }

  private static RenditionJob renditionJob(UUID sourceNamespaceId) {
    return RenditionJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(sourceNamespaceId))
                .setRelativeKey("movie.mkv"))
        .setRendition(RenditionSpec.newBuilder().setRenditionName("720p"))
        .build();
  }

  private static SegmentUploadMetadata metadata(
      UUID workerSessionId, WorkerIdentity worker, RenditionJob job, long contentLength) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(toProto(workerSessionId))
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setRenditionName(job.getRendition().getRenditionName())
        .setSegmentName("segment0.ts")
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP2T)
        .setContentLengthBytes(contentLength)
        .build();
  }

  private static final class RecordingUploadResponseObserver
      implements StreamObserver<UploadSegmentResponse> {

    private Throwable error;

    @Override
    public void onNext(UploadSegmentResponse value) {
      throw new AssertionError("Rejected or cancelled upload should not receive a response");
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Rejected or cancelled upload should not complete successfully");
    }

    private Throwable error() {
      return error;
    }
  }

  private static final class IgnoringResponseObserver
      implements StreamObserver<WorkerSessionResponse> {

    @Override
    public void onNext(WorkerSessionResponse value) {
      assertThat(value.getCommandCase())
          .isIn(
              WorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
              WorkerSessionResponse.CommandCase.START_RENDITION);
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Registered worker should remain connected", throwable);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Registered worker should remain connected");
    }
  }
}
