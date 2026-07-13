package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartRenditionCommand;
import com.streamarr.transcode.v1.StopRenditionCommand;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LiveWorkerConnectionRegistry {

  private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

  private final ConcurrentHashMap<UUID, WorkerConnection> connections = new ConcurrentHashMap<>();

  synchronized UUID register(
      UUID workerId,
      WorkerRegistration registration,
      StreamObserver<WorkerSessionResponse> responseObserver) {
    var connection =
        new WorkerConnection(
            UUID.randomUUID(),
            registration.getWorker(),
            registration.getAvailableSlots(),
            responseObserver);
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
    for (var connection : connections.values()) {
      if (connection.tryDispatch(job)) {
        return true;
      }
    }
    return false;
  }

  boolean stopRendition(UUID jobAttemptId) {
    for (var connection : connections.values()) {
      if (connection.tryStop(jobAttemptId)) {
        return true;
      }
    }
    return false;
  }

  void finishJobAttempt(UUID workerId, UUID workerSessionId, UUID jobAttemptId) {
    var connection = connections.get(workerId);
    if (connection != null && connection.workerSessionId().equals(workerSessionId)) {
      connection.finishJobAttempt(jobAttemptId);
    }
  }

  boolean authorizesUpload(UUID authenticatedWorkerId, SegmentUploadMetadata metadata) {
    var connection = connections.get(authenticatedWorkerId);
    return connection != null && connection.authorizesUpload(metadata);
  }

  private static final class WorkerConnection {

    private final UUID workerSessionId;
    private final WorkerIdentity worker;
    private final int maximumActiveRenditions;
    private final StreamObserver<WorkerSessionResponse> responseObserver;
    private final Map<UUID, RenditionJob> activeRenditions = new HashMap<>();

    private WorkerConnection(
        UUID workerSessionId,
        WorkerIdentity worker,
        int maximumActiveRenditions,
        StreamObserver<WorkerSessionResponse> responseObserver) {
      this.workerSessionId = workerSessionId;
      this.worker = worker;
      this.maximumActiveRenditions = maximumActiveRenditions;
      this.responseObserver = responseObserver;
    }

    private UUID workerSessionId() {
      return workerSessionId;
    }

    private synchronized void accept() {
      var accepted =
          WorkerSessionAccepted.newBuilder()
              .setWorkerSessionId(toProto(workerSessionId))
              .setHeartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS);
      responseObserver.onNext(
          WorkerSessionResponse.newBuilder().setSessionAccepted(accepted).build());
    }

    private synchronized boolean tryDispatch(RenditionJob job) {
      if (maximumActiveRenditions < 1 || activeRenditions.size() >= maximumActiveRenditions) {
        return false;
      }

      activeRenditions.put(fromProto(job.getJobAttemptId()), job);
      var command = StartRenditionCommand.newBuilder().setTarget(worker).setJob(job).build();
      responseObserver.onNext(
          WorkerSessionResponse.newBuilder().setStartRendition(command).build());
      return true;
    }

    private synchronized boolean tryStop(UUID jobAttemptId) {
      if (activeRenditions.remove(jobAttemptId) == null) {
        return false;
      }

      var command =
          StopRenditionCommand.newBuilder()
              .setTarget(worker)
              .setJobAttemptId(toProto(jobAttemptId))
              .build();
      responseObserver.onNext(WorkerSessionResponse.newBuilder().setStopRendition(command).build());
      return true;
    }

    private synchronized void finishJobAttempt(UUID jobAttemptId) {
      activeRenditions.remove(jobAttemptId);
    }

    private synchronized boolean authorizesUpload(SegmentUploadMetadata metadata) {
      if (!toProto(workerSessionId).equals(metadata.getWorkerSessionId())
          || !worker.equals(metadata.getWorker())) {
        return false;
      }

      var job = activeRenditions.get(fromProto(metadata.getJobAttemptId()));
      return job != null
          && job.getStreamSessionId().equals(metadata.getStreamSessionId())
          && job.getJobId().equals(metadata.getJobId())
          && job.getRendition().getRenditionName().equals(metadata.getRenditionName());
    }

    private synchronized void closeAsReplaced() {
      activeRenditions.clear();
      responseObserver.onError(
          Status.ABORTED.withDescription("Worker connection replaced").asRuntimeException());
    }
  }
}
