package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.StartRenditionCommand;
import com.streamarr.transcode.v1.StopRenditionCommand;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LiveWorkerConnectionRegistry {

  private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

  private final ConcurrentHashMap<UUID, WorkerConnection> connections = new ConcurrentHashMap<>();

  synchronized UUID register(
      UUID workerId,
      WorkerIdentity worker,
      StreamObserver<WorkerSessionResponse> responseObserver) {
    var connection = new WorkerConnection(UUID.randomUUID(), worker, responseObserver);
    var replaced = connections.put(workerId, connection);
    if (replaced != null) {
      replaced.closeAsReplaced();
    }
    connection.accept();
    return connection.workerSessionId();
  }

  void disconnect(UUID workerId, UUID workerSessionId) {
    connections.computeIfPresent(
        workerId,
        (_, connection) ->
            connection.workerSessionId().equals(workerSessionId) ? null : connection);
  }

  boolean dispatch(RenditionJob job) {
    var connection = connections.values().stream().findFirst().orElse(null);
    if (connection == null) {
      return false;
    }

    connection.sendStartRendition(job);
    return true;
  }

  boolean stopRendition(UUID jobAttemptId) {
    var connection = connections.values().stream().findFirst().orElse(null);
    if (connection == null) {
      return false;
    }

    connection.sendStopRendition(jobAttemptId);
    return true;
  }

  private static Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private record WorkerConnection(
      UUID workerSessionId,
      WorkerIdentity worker,
      StreamObserver<WorkerSessionResponse> responseObserver) {

    private synchronized void accept() {
      var accepted =
          WorkerSessionAccepted.newBuilder()
              .setWorkerSessionId(uuid(workerSessionId))
              .setHeartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS);
      responseObserver.onNext(
          WorkerSessionResponse.newBuilder().setSessionAccepted(accepted).build());
    }

    private synchronized void sendStartRendition(RenditionJob job) {
      var command = StartRenditionCommand.newBuilder().setTarget(worker).setJob(job).build();
      responseObserver.onNext(
          WorkerSessionResponse.newBuilder().setStartRendition(command).build());
    }

    private synchronized void sendStopRendition(UUID jobAttemptId) {
      var command =
          StopRenditionCommand.newBuilder()
              .setTarget(worker)
              .setJobAttemptId(uuid(jobAttemptId))
              .build();
      responseObserver.onNext(WorkerSessionResponse.newBuilder().setStopRendition(command).build());
    }

    private synchronized void closeAsReplaced() {
      responseObserver.onError(
          Status.ABORTED.withDescription("Worker connection replaced").asRuntimeException());
    }
  }
}
