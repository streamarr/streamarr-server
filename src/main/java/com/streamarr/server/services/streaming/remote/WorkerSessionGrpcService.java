package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.v1.WorkerSessionRequest;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;

final class WorkerSessionGrpcService
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

  private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

  @Override
  public StreamObserver<WorkerSessionRequest> workerSession(
      StreamObserver<WorkerSessionResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    return new RegistrationObserver(authenticatedWorkerId, responseObserver);
  }

  private static Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private static UUID uuid(Uuid value) {
    return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
  }

  private static final class RegistrationObserver implements StreamObserver<WorkerSessionRequest> {

    private final UUID authenticatedWorkerId;
    private final StreamObserver<WorkerSessionResponse> responseObserver;
    private boolean registered;

    private RegistrationObserver(
        UUID authenticatedWorkerId, StreamObserver<WorkerSessionResponse> responseObserver) {
      this.authenticatedWorkerId = authenticatedWorkerId;
      this.responseObserver = responseObserver;
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

      var reportedWorkerId = uuid(request.getRegistration().getWorker().getWorkerId());
      if (!authenticatedWorkerId.equals(reportedWorkerId)) {
        reject(
            Status.PERMISSION_DENIED.withDescription(
                "Registered worker identity does not match authenticated identity"));
        return;
      }

      registered = true;
      var accepted =
          WorkerSessionAccepted.newBuilder()
              .setWorkerSessionId(uuid(UUID.randomUUID()))
              .setHeartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS);
      responseObserver.onNext(
          WorkerSessionResponse.newBuilder().setSessionAccepted(accepted).build());
    }

    @Override
    public void onError(Throwable ignored) {}

    @Override
    public void onCompleted() {
      responseObserver.onCompleted();
    }

    private void reject(Status status) {
      responseObserver.onError(status.asRuntimeException());
    }
  }
}
