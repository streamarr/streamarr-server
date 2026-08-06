package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
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

  /** Receives frames after the service has already rejected an upload call. */
  private static final StreamObserver<UploadSegmentRequest> IGNORED_UPLOAD_OBSERVER =
      new StreamObserver<>() {
        @Override
        public void onNext(UploadSegmentRequest value) {
          // The response has already ended with an error.
        }

        @Override
        public void onError(Throwable throwable) {
          // The response has already ended with an error.
        }

        @Override
        public void onCompleted() {
          // The response has already ended with an error.
        }
      };

  // A ladder's variants upload in parallel, so the allowance has to clear a full ladder; it is a
  // blast-radius bound on one worker's wedged streams, not a throughput limit.
  private static final int MAXIMUM_SEGMENT_UPLOADS_PER_WORKER = 8;

  private final LiveWorkerConnectionRegistry workerConnections;
  private final SegmentStore segmentStore;
  private final SegmentUploadAdmission segmentUploadAdmission;

  WorkerSessionGrpcService(
      LiveWorkerConnectionRegistry workerConnections, SegmentStore segmentStore) {
    this.workerConnections = workerConnections;
    this.segmentStore = segmentStore;
    segmentUploadAdmission =
        new SegmentUploadAdmission(
            MAXIMUM_CONCURRENT_SEGMENT_UPLOADS,
            MAXIMUM_BUFFERED_SEGMENT_BYTES,
            MAXIMUM_SEGMENT_UPLOADS_PER_WORKER);
  }

  @Override
  public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
      StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    return new RegistrationObserver(authenticatedWorkerId, responseObserver, workerConnections);
  }

  @Override
  public StreamObserver<UploadSegmentRequest> uploadSegment(
      StreamObserver<UploadSegmentResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    if (authenticatedWorkerId == null) {
      // The identity interceptor rejects before reaching here, so this means the server was wired
      // without it. Fail closed rather than admit an upload no per-worker allowance can bound.
      log.error("Rejecting segment upload with no authenticated worker identity in context");
      responseObserver.onError(
          Status.UNAUTHENTICATED
              .withDescription("Worker identity is required")
              .asRuntimeException());
      return IGNORED_UPLOAD_OBSERVER;
    }

    var ticket = segmentUploadAdmission.tryAdmit(authenticatedWorkerId);
    if (ticket.isEmpty()) {
      log.warn(
          "Rejecting segment upload from worker {}: concurrent upload limit reached",
          authenticatedWorkerId);
      responseObserver.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("Concurrent segment upload limit reached")
              .asRuntimeException());
      return IGNORED_UPLOAD_OBSERVER;
    }
    return SegmentUploadObserver.builder()
        .authenticatedWorkerId(authenticatedWorkerId)
        .workerConnections(workerConnections)
        .segmentStore(segmentStore)
        .responseObserver(responseObserver)
        .uploadTicket(ticket.get())
        .build();
  }

  private static final class RegistrationObserver
      implements StreamObserver<EstablishWorkerSessionRequest> {

    private final UUID authenticatedWorkerId;
    private final StreamObserver<EstablishWorkerSessionResponse> responseObserver;
    private final LiveWorkerConnectionRegistry workerConnections;
    private boolean registered;
    private UUID workerSessionId;

    private RegistrationObserver(
        UUID authenticatedWorkerId,
        StreamObserver<EstablishWorkerSessionResponse> responseObserver,
        LiveWorkerConnectionRegistry workerConnections) {
      this.authenticatedWorkerId = authenticatedWorkerId;
      this.responseObserver = responseObserver;
      this.workerConnections = workerConnections;
    }

    @Override
    public void onNext(EstablishWorkerSessionRequest request) {
      if (authenticatedWorkerId == null) {
        reject(Status.UNAUTHENTICATED.withDescription("Worker identity is required"));
        return;
      }
      if (registered) {
        handleSessionEvent(request);
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

      workerSessionId =
          workerConnections.register(
              authenticatedWorkerId, request.getRegistration(), responseObserver);
      registered = true;
    }

    @Override
    public void onError(Throwable throwable) {
      log.warn(
          "Worker {} session {} control stream failed",
          authenticatedWorkerId,
          workerSessionId,
          throwable);
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

    private void handleSessionEvent(EstablishWorkerSessionRequest request) {
      switch (request.getEventCase()) {
        case JOB_ATTEMPT_STARTED ->
            log.debug(
                "Worker {} started job attempt {}",
                authenticatedWorkerId,
                fromProto(request.getJobAttemptStarted().getJobAttemptId()));
        case JOB_ATTEMPT_FAILED -> reportFailedJobAttempt(request.getJobAttemptFailed());
        case JOB_ATTEMPT_COMPLETED ->
            finishOrWarn(request.getJobAttemptCompleted().getJobAttemptId(), "completed");
        case JOB_ATTEMPT_STOPPED ->
            finishOrWarn(request.getJobAttemptStopped().getJobAttemptId(), "stopped");
        default ->
            log.warn(
                "Ignoring unexpected {} event on established session of worker {}",
                request.getEventCase(),
                authenticatedWorkerId);
      }
    }

    private void reportFailedJobAttempt(JobAttemptFailed failed) {
      finish(failed.getJobAttemptId(), failed.getFailure().name())
          .ifPresentOrElse(
              job ->
                  log.warn(
                      "Worker {} failed variant {} of stream session {}: {}",
                      authenticatedWorkerId,
                      job.getVariant().getVariantLabel(),
                      fromProto(job.getStreamSessionId()),
                      failed.getFailure()),
              () ->
                  log.warn(
                      "Worker {} failed unknown job attempt {}: {}",
                      authenticatedWorkerId,
                      fromProto(failed.getJobAttemptId()),
                      failed.getFailure()));
    }

    private Optional<VariantJob> finish(Uuid jobAttemptId, String detail) {
      var released =
          workerConnections.releaseJobAttempt(
              authenticatedWorkerId, workerSessionId, fromProto(jobAttemptId));
      released.ifPresent(
          job ->
              log.debug(
                  "Worker {} ended job attempt {} for stream session {} variant {} ({})",
                  authenticatedWorkerId,
                  fromProto(jobAttemptId),
                  fromProto(job.getStreamSessionId()),
                  job.getVariant().getVariantLabel(),
                  detail));
      return released;
    }

    private void finishOrWarn(Uuid jobAttemptId, String detail) {
      if (finish(jobAttemptId, detail).isPresent()) {
        return;
      }
      log.warn(
          "Worker {} reported {} for unknown job attempt {}",
          authenticatedWorkerId,
          detail,
          fromProto(jobAttemptId));
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
