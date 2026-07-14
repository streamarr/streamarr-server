package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.WorkerSessionRequest;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WorkerSessionGrpcService
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

  private static final int MAXIMUM_CONCURRENT_SEGMENT_UPLOADS = 32;
  private static final long MAXIMUM_BUFFERED_SEGMENT_BYTES = 64L * 1024 * 1024;

  private final LiveWorkerConnectionRegistry workerConnections;
  private final SegmentStore segmentStore;
  private final SegmentUploadAdmission segmentUploadAdmission;

  WorkerSessionGrpcService(
      LiveWorkerConnectionRegistry workerConnections, SegmentStore segmentStore) {
    this.workerConnections = workerConnections;
    this.segmentStore = segmentStore;
    segmentUploadAdmission =
        new SegmentUploadAdmission(
            MAXIMUM_CONCURRENT_SEGMENT_UPLOADS, MAXIMUM_BUFFERED_SEGMENT_BYTES);
  }

  @Override
  public StreamObserver<WorkerSessionRequest> workerSession(
      StreamObserver<WorkerSessionResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    return new RegistrationObserver(authenticatedWorkerId, responseObserver, workerConnections);
  }

  @Override
  public StreamObserver<UploadSegmentRequest> uploadSegment(
      StreamObserver<UploadSegmentResponse> responseObserver) {
    if (!segmentUploadAdmission.tryOpen()) {
      log.warn(
          "Rejecting segment upload from worker {}: concurrent upload limit reached",
          WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get());
      responseObserver.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("Concurrent segment upload limit reached")
              .asRuntimeException());
      return new IgnoredUploadObserver();
    }
    return SegmentUploadObserver.builder()
        .authenticatedWorkerId(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get())
        .workerConnections(workerConnections)
        .segmentStore(segmentStore)
        .responseObserver(responseObserver)
        .uploadAdmission(segmentUploadAdmission)
        .build();
  }

  private static final class IgnoredUploadObserver implements StreamObserver<UploadSegmentRequest> {

    @Override
    public void onNext(UploadSegmentRequest value) {
      // The call was rejected before an upload observer was created.
    }

    @Override
    public void onError(Throwable throwable) {
      // The call was rejected before an upload observer was created.
    }

    @Override
    public void onCompleted() {
      // The call was rejected before an upload observer was created.
    }
  }

  private static final class RegistrationObserver implements StreamObserver<WorkerSessionRequest> {

    private final UUID authenticatedWorkerId;
    private final StreamObserver<WorkerSessionResponse> responseObserver;
    private final LiveWorkerConnectionRegistry workerConnections;
    private boolean registered;
    private UUID workerSessionId;

    private RegistrationObserver(
        UUID authenticatedWorkerId,
        StreamObserver<WorkerSessionResponse> responseObserver,
        LiveWorkerConnectionRegistry workerConnections) {
      this.authenticatedWorkerId = authenticatedWorkerId;
      this.responseObserver = responseObserver;
      this.workerConnections = workerConnections;
    }

    @Override
    public void onNext(WorkerSessionRequest request) {
      if (registered) {
        finishJobAttempt(request);
        return;
      }
      if (!request.hasRegistration() || !request.getRegistration().hasWorker()) {
        reject(
            Status.INVALID_ARGUMENT.withDescription("Worker session must begin with registration"));
        return;
      }

      var reportedWorkerId = fromProto(request.getRegistration().getWorker().getWorkerId());
      if (!authenticatedWorkerId.equals(reportedWorkerId)) {
        reject(
            Status.PERMISSION_DENIED.withDescription(
                "Registered worker identity does not match authenticated identity"));
        return;
      }

      registered = true;
      workerSessionId =
          workerConnections.register(
              authenticatedWorkerId, request.getRegistration(), responseObserver);
    }

    @Override
    public void onError(Throwable ignored) {
      disconnect();
    }

    @Override
    public void onCompleted() {
      disconnect();
      responseObserver.onCompleted();
    }

    private void disconnect() {
      if (registered) {
        workerConnections.disconnect(authenticatedWorkerId, workerSessionId);
      }
    }

    private void finishJobAttempt(WorkerSessionRequest request) {
      switch (request.getEventCase()) {
        case JOB_ATTEMPT_STARTED ->
            log.debug(
                "Worker {} started job attempt {}",
                authenticatedWorkerId,
                fromProto(request.getJobAttemptStarted().getJobAttemptId()));
        case JOB_ATTEMPT_FAILED -> reportFailedJobAttempt(request.getJobAttemptFailed());
        case JOB_ATTEMPT_COMPLETED -> finish(request.getJobAttemptCompleted().getJobAttemptId());
        case JOB_ATTEMPT_STOPPED -> finish(request.getJobAttemptStopped().getJobAttemptId());
        default ->
            log.warn(
                "Ignoring unexpected {} event on established session of worker {}",
                request.getEventCase(),
                authenticatedWorkerId);
      }
    }

    private void reportFailedJobAttempt(JobAttemptFailed failed) {
      finish(failed.getJobAttemptId())
          .ifPresentOrElse(
              job ->
                  log.warn(
                      "Worker {} failed rendition {} of stream session {}: {}",
                      authenticatedWorkerId,
                      job.getRendition().getRenditionName(),
                      fromProto(job.getStreamSessionId()),
                      failed.getFailure()),
              () ->
                  log.warn(
                      "Worker {} failed unknown job attempt {}: {}",
                      authenticatedWorkerId,
                      fromProto(failed.getJobAttemptId()),
                      failed.getFailure()));
    }

    private Optional<RenditionJob> finish(com.streamarr.transcode.v1.Uuid jobAttemptId) {
      return workerConnections.finishJobAttempt(
          authenticatedWorkerId, workerSessionId, fromProto(jobAttemptId));
    }

    private void reject(Status status) {
      log.warn(
          "Rejecting worker session of worker {}: {}",
          authenticatedWorkerId,
          status.getDescription());
      responseObserver.onError(status.asRuntimeException());
    }
  }
}
