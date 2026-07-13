package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
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

  @Override
  public StreamObserver<WorkerSessionRequest> workerSession(
      StreamObserver<WorkerSessionResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    return new RegistrationObserver(authenticatedWorkerId, responseObserver, workerConnections);
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
              authenticatedWorkerId, request.getRegistration().getWorker(), responseObserver);
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

    private void reject(Status status) {
      responseObserver.onError(status.asRuntimeException());
    }
  }
}
