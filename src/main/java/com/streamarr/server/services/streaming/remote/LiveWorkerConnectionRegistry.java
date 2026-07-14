package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartVariantCommand;
import com.streamarr.transcode.v1.StopVariantCommand;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class LiveWorkerConnectionRegistry {

  private final ConcurrentHashMap<UUID, WorkerConnection> connections = new ConcurrentHashMap<>();

  synchronized UUID register(
      UUID workerId,
      WorkerRegistration registration,
      StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
    var connection = new WorkerConnection(UUID.randomUUID(), registration, responseObserver);
    connection.accept();
    var replaced = connections.put(workerId, connection);
    if (replaced == null) {
      log.info("Worker {} connected", workerId);
      return connection.workerSessionId();
    }

    var abandonedVariants = replaced.closeAsReplaced();
    log.warn(
        "Worker {} reconnected; abandoning {} active variant job(s) from its previous"
            + " connection",
        workerId,
        abandonedVariants);
    return connection.workerSessionId();
  }

  void disconnect(UUID workerId, UUID workerSessionId) {
    var connection = connections.get(workerId);
    if (connection != null
        && connection.workerSessionId().equals(workerSessionId)
        && connections.remove(workerId, connection)) {
      log.info("Worker {} disconnected", workerId);
    }
  }

  boolean dispatch(VariantJob job) {
    for (var connection : connections.values()) {
      if (connection.tryDispatch(job)) {
        return true;
      }
    }
    return false;
  }

  boolean stopVariant(UUID jobAttemptId) {
    for (var connection : connections.values()) {
      if (connection.tryStop(jobAttemptId)) {
        return true;
      }
    }
    return false;
  }

  void stopStreamSession(UUID streamSessionId) {
    connections.values().forEach(connection -> connection.stopStreamSession(streamSessionId));
  }

  boolean isRunning(UUID streamSessionId) {
    return connections.values().stream()
        .anyMatch(connection -> connection.isRunning(streamSessionId));
  }

  boolean isRunning(UUID streamSessionId, String variantLabel) {
    return connections.values().stream()
        .anyMatch(connection -> connection.isRunning(streamSessionId, variantLabel));
  }

  boolean hasConnectedWorker() {
    return !connections.isEmpty();
  }

  int availableSlots() {
    return connections.values().stream().mapToInt(WorkerConnection::availableSlots).sum();
  }

  Optional<VariantJob> releaseJobAttempt(UUID workerId, UUID workerSessionId, UUID jobAttemptId) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return Optional.empty();
    }
    return connection.releaseJobAttempt(jobAttemptId);
  }

  boolean authorizesUpload(UUID authenticatedWorkerId, SegmentUploadMetadata metadata) {
    var connection = connections.get(authenticatedWorkerId);
    return connection != null && connection.authorizesUpload(metadata);
  }

  private static final class WorkerConnection {

    private final UUID workerSessionId;
    private final WorkerIdentity worker;
    private final Set<Uuid> sourceNamespaceIds;
    private final int maximumActiveVariants;
    private final StreamObserver<EstablishWorkerSessionResponse> responseObserver;
    private final Map<UUID, VariantJob> activeVariants = new HashMap<>();

    private WorkerConnection(
        UUID workerSessionId,
        WorkerRegistration registration,
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      this.workerSessionId = workerSessionId;
      worker = registration.getWorker();
      sourceNamespaceIds = Set.copyOf(registration.getCapabilities().getSourceNamespaceIdsList());
      maximumActiveVariants = registration.getAvailableSlots();
      this.responseObserver = responseObserver;
    }

    private UUID workerSessionId() {
      return workerSessionId;
    }

    private synchronized void accept() {
      var accepted =
          WorkerSessionAccepted.newBuilder().setWorkerSessionId(toProto(workerSessionId));
      responseObserver.onNext(
          EstablishWorkerSessionResponse.newBuilder().setSessionAccepted(accepted).build());
    }

    private synchronized boolean tryDispatch(VariantJob job) {
      if (!canAccessSource(job)
          || maximumActiveVariants < 1
          || activeVariants.size() >= maximumActiveVariants) {
        return false;
      }

      var command = StartVariantCommand.newBuilder().setTarget(worker).setJob(job).build();
      if (!trySend(EstablishWorkerSessionResponse.newBuilder().setStartVariant(command).build())) {
        return false;
      }
      activeVariants.put(fromProto(job.getJobAttemptId()), job);
      return true;
    }

    private boolean canAccessSource(VariantJob job) {
      return job.hasSource() && sourceNamespaceIds.contains(job.getSource().getSourceNamespaceId());
    }

    private synchronized boolean tryStop(UUID jobAttemptId) {
      if (activeVariants.remove(jobAttemptId) == null) {
        return false;
      }

      var command =
          StopVariantCommand.newBuilder()
              .setTarget(worker)
              .setJobAttemptId(toProto(jobAttemptId))
              .build();
      trySend(EstablishWorkerSessionResponse.newBuilder().setStopVariant(command).build());
      return true;
    }

    /** A send can fail when the worker call died but its disconnect has not been reaped yet. */
    private boolean trySend(EstablishWorkerSessionResponse response) {
      try {
        responseObserver.onNext(response);
        return true;
      } catch (RuntimeException e) {
        log.warn("Failed to send command over worker session {}", workerSessionId, e);
        return false;
      }
    }

    private synchronized Optional<VariantJob> releaseJobAttempt(UUID jobAttemptId) {
      return Optional.ofNullable(activeVariants.remove(jobAttemptId));
    }

    private synchronized int availableSlots() {
      return Math.max(0, maximumActiveVariants - activeVariants.size());
    }

    private synchronized void stopStreamSession(UUID streamSessionId) {
      activeVariants.entrySet().stream()
          .filter(entry -> fromProto(entry.getValue().getStreamSessionId()).equals(streamSessionId))
          .map(Map.Entry::getKey)
          .toList()
          .forEach(this::tryStop);
    }

    private synchronized boolean isRunning(UUID streamSessionId) {
      return activeVariants.values().stream()
          .anyMatch(job -> fromProto(job.getStreamSessionId()).equals(streamSessionId));
    }

    private synchronized boolean isRunning(UUID streamSessionId, String variantLabel) {
      return activeVariants.values().stream()
          .anyMatch(
              job ->
                  fromProto(job.getStreamSessionId()).equals(streamSessionId)
                      && job.getVariant().getVariantLabel().equals(variantLabel));
    }

    private synchronized boolean authorizesUpload(SegmentUploadMetadata metadata) {
      if (!toProto(workerSessionId).equals(metadata.getWorkerSessionId())
          || !worker.equals(metadata.getWorker())) {
        return false;
      }

      var job = activeVariants.get(fromProto(metadata.getJobAttemptId()));
      return job != null
          && job.getStreamSessionId().equals(metadata.getStreamSessionId())
          && job.getJobId().equals(metadata.getJobId())
          && job.getVariant().getVariantLabel().equals(metadata.getVariantLabel());
    }

    private synchronized int closeAsReplaced() {
      var abandonedVariants = activeVariants.size();
      activeVariants.clear();
      try {
        responseObserver.onError(
            Status.ABORTED.withDescription("Worker connection replaced").asRuntimeException());
      } catch (RuntimeException _) {
        // The previous call is already dead; the replacement proceeds regardless.
      }
      return abandonedVariants;
    }
  }
}
