package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.WorkerSessionRequest;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class WorkerSessionGrpcService
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

  private final LiveWorkerConnectionRegistry workerConnections;
  private final SegmentStore segmentStore;

  @Override
  public StreamObserver<WorkerSessionRequest> workerSession(
      StreamObserver<WorkerSessionResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    return new RegistrationObserver(authenticatedWorkerId, responseObserver, workerConnections);
  }

  @Override
  public StreamObserver<UploadSegmentRequest> uploadSegment(
      StreamObserver<UploadSegmentResponse> responseObserver) {
    return SegmentUploadObserver.builder()
        .authenticatedWorkerId(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get())
        .workerConnections(workerConnections)
        .segmentStore(segmentStore)
        .responseObserver(responseObserver)
        .build();
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
      var jobAttemptId =
          switch (request.getEventCase()) {
            case JOB_ATTEMPT_FAILED -> request.getJobAttemptFailed().getJobAttemptId();
            case JOB_ATTEMPT_COMPLETED -> request.getJobAttemptCompleted().getJobAttemptId();
            case JOB_ATTEMPT_STOPPED -> request.getJobAttemptStopped().getJobAttemptId();
            default -> null;
          };
      if (jobAttemptId != null) {
        workerConnections.finishJobAttempt(
            authenticatedWorkerId, workerSessionId, fromProto(jobAttemptId));
      }
    }

    private void reject(Status status) {
      responseObserver.onError(status.asRuntimeException());
    }
  }
}
