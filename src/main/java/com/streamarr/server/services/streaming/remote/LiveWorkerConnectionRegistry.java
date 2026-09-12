package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.StartVariantCommand;
import com.streamarr.transcode.v1.StopVariantCommand;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class LiveWorkerConnectionRegistry {

  private final ConcurrentHashMap<UUID, WorkerConnection> connections = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<ProbeAttemptResult>> pendingProbes =
      new ConcurrentHashMap<>();

  synchronized UUID register(
      UUID workerId,
      WorkerRegistration registration,
      StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
    var connection = new WorkerConnection(UUID.randomUUID(), registration, responseObserver);
    WorkerConnection replaced;
    List<VariantJob> abandonedJobs;
    synchronized (connection) {
      replaced = connections.put(workerId, connection);
      try {
        connection.accept();
      } catch (RuntimeException e) {
        rollbackRegistration(workerId, connection, replaced);
        throw e;
      }

      abandonedJobs = replaced == null ? List.of() : replaced.abandonAllJobsWithoutWaiting();
    }
    if (replaced == null) {
      log.info("Worker {} connected", workerId);
      return connection.workerSessionId();
    }

    replaced.closeAsReplaced();
    abandonedJobs.forEach(job -> logAbandonedJob(job, "replaced"));
    log.warn(
        "Worker {} reconnected; abandoning {} active variant job(s) from its previous"
            + " connection",
        workerId,
        abandonedJobs.size());
    return connection.workerSessionId();
  }

  private void rollbackRegistration(
      UUID workerId, WorkerConnection connection, WorkerConnection replaced) {
    if (replaced == null) {
      connections.remove(workerId, connection);
      return;
    }

    connections.replace(workerId, connection, replaced);
  }

  synchronized void disconnect(UUID workerId, UUID workerSessionId) {
    var connection = connections.get(workerId);
    if (connection != null
        && connection.workerSessionId().equals(workerSessionId)
        && connections.remove(workerId, connection)) {
      connection
          .abandonAllJobsWithoutWaiting()
          .forEach(job -> logAbandonedJob(job, "disconnected"));
      log.info("Worker {} disconnected", workerId);
    }
  }

  boolean dispatch(VariantJob job) {
    for (var connection : connections.values()) {
      if (tryDispatchUnlessDisconnected(connection, job)) {
        return true;
      }
    }
    return false;
  }

  Optional<Future<ProbeAttemptResult>> dispatchProbe(ProbeRequest request) {
    for (var connection : connections.values()) {
      var dispatched = connection.tryDispatchProbe(request);
      if (dispatched.isEmpty()) {
        continue;
      }

      if (connections.containsValue(connection)) {
        return dispatched;
      }

      connection.abandonProbe(fromProto(request.getProbeAttemptId()));
    }

    return Optional.empty();
  }

  synchronized boolean completeProbe(
      UUID workerId, UUID workerSessionId, ProbeAttemptResult result) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return false;
    }

    return connection.completeProbe(result);
  }

  boolean dispatchTo(ExecutionTargetId target, VariantJob job) {
    for (var connection : connections.values()) {
      if (connection.workerSessionId().toString().equals(target.value())) {
        return tryDispatchUnlessDisconnected(connection, job);
      }
    }
    return false;
  }

  /**
   * {@code tryDispatch} is synchronized, but a disconnect drains {@code activeVariants} without
   * that monitor, so the drain can land between the send and the put. The job would then be
   * orphaned: held by a dropped connection, never logged as abandoned.
   */
  private boolean tryDispatchUnlessDisconnected(WorkerConnection connection, VariantJob job) {
    if (!connection.tryDispatch(job)) {
      return false;
    }
    if (!connections.containsValue(connection)) {
      connection.releaseJobAttempt(fromProto(job.getJobAttemptId()));
      return false;
    }
    return true;
  }

  Set<ExecutionTargetId> eligibleWorkers(UUID sourceNamespaceId) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .map(connection -> new ExecutionTargetId(connection.workerSessionId().toString()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  boolean stopVariant(UUID streamSessionId, String variantLabel) {
    for (var connection : connections.values()) {
      if (connection.stopVariant(streamSessionId, variantLabel)) {
        return true;
      }
    }
    return false;
  }

  void stopStreamSession(UUID streamSessionId) {
    connections.values().forEach(connection -> connection.stopStreamSession(streamSessionId));
  }

  /** This warn is the only record an abandoned job leaves — nothing else persists it. */
  private void logAbandonedJob(VariantJob job, String reason) {
    log.warn(
        "Abandoning job attempt {} for stream session {} variant {} ({})",
        fromProto(job.getJobAttemptId()),
        fromProto(job.getStreamSessionId()),
        job.getVariant().getVariantLabel(),
        reason);
  }

  boolean isRunning(UUID streamSessionId, String variantLabel) {
    return connections.values().stream()
        .anyMatch(connection -> connection.isRunning(streamSessionId, variantLabel));
  }

  boolean hasConnectedWorker(UUID sourceNamespaceId) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .anyMatch(connection -> connection.canAccessSourceNamespace(sourceNamespace));
  }

  int availableSlots(UUID sourceNamespaceId) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .mapToInt(WorkerConnection::availableSlots)
        .sum();
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

  boolean publishIfAuthorized(
      UUID authenticatedWorkerId, SegmentUploadMetadata metadata, Runnable publication) {
    // Unsynchronized on purpose: a segment publish is a filesystem move and must not queue
    // behind worker register/disconnect. Stale lookups fail the connection's re-check.
    var connection = connections.get(authenticatedWorkerId);
    if (connection == null) {
      return false;
    }
    return connection.publishIfStillAuthorized(metadata, publication);
  }

  private final class WorkerConnection {

    private final UUID workerSessionId;
    private final WorkerIdentity worker;
    private final Set<Uuid> sourceNamespaceIds;
    private final Set<Integer> probeVersions;
    private final int maximumActiveVariants;
    private final StreamObserver<EstablishWorkerSessionResponse> responseObserver;

    private final Map<UUID, VariantJob> activeVariants = new ConcurrentHashMap<>();
    private final Map<UUID, PendingProbe> activeProbes = new ConcurrentHashMap<>();

    private WorkerConnection(
        UUID workerSessionId,
        WorkerRegistration registration,
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      this.workerSessionId = workerSessionId;
      worker = registration.getWorker();
      sourceNamespaceIds = Set.copyOf(registration.getCapabilities().getSourceNamespaceIdsList());
      probeVersions = Set.copyOf(registration.getCapabilities().getProbeVersionsList());
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
      if (!canAccessSource(job) || availableSlots() == 0) {
        return false;
      }

      var command = StartVariantCommand.newBuilder().setTarget(worker).setJob(job).build();
      if (!trySend(EstablishWorkerSessionResponse.newBuilder().setStartVariant(command).build())) {
        return false;
      }
      activeVariants.put(fromProto(job.getJobAttemptId()), job);
      return true;
    }

    private synchronized Optional<Future<ProbeAttemptResult>> tryDispatchProbe(
        ProbeRequest request) {
      if (!probeVersions.contains(request.getProbeVersion())
          || !request.hasSource()
          || !canAccessSourceNamespace(request.getSource().getSourceNamespaceId())
          || availableSlots() == 0) {
        return Optional.empty();
      }

      var attemptId = fromProto(request.getProbeAttemptId());
      var pending = new PendingProbe(request, new CompletableFuture<>());
      if (pendingProbes.putIfAbsent(attemptId, pending.result()) != null) {
        return Optional.empty();
      }

      activeProbes.put(attemptId, pending);
      pending.result().whenComplete((_, _) -> cancelProbeIfRequested(pending));
      var command = StartProbeCommand.newBuilder().setTarget(worker).setRequest(request).build();
      if (!trySend(EstablishWorkerSessionResponse.newBuilder().setStartProbe(command).build())) {
        activeProbes.remove(attemptId, pending);
        pendingProbes.remove(attemptId, pending.result());
        return Optional.empty();
      }

      return Optional.of(pending.result());
    }

    private void cancelProbeIfRequested(PendingProbe pending) {
      if (!pending.result().isCancelled()) {
        return;
      }

      synchronized (this) {
        var attemptId = fromProto(pending.request().getProbeAttemptId());
        if (activeProbes.get(attemptId) != pending) {
          return;
        }

        var command =
            CancelProbeCommand.newBuilder()
                .setTarget(worker)
                .setProbeAttemptId(pending.request().getProbeAttemptId())
                .build();
        trySend(EstablishWorkerSessionResponse.newBuilder().setCancelProbe(command).build());
      }
    }

    private boolean completeProbe(ProbeAttemptResult result) {
      var attemptId = fromProto(result.getProbeAttemptId());
      var pending = activeProbes.remove(attemptId);
      if (pending == null) {
        return false;
      }

      try {
        return finishProbe(pending, result);
      } finally {
        pendingProbes.remove(attemptId, pending.result());
      }
    }

    private boolean finishProbe(PendingProbe pending, ProbeAttemptResult result) {
      if (result.getProbeVersion() != pending.request().getProbeVersion()) {
        pending
            .result()
            .completeExceptionally(
                new ProbeExecutionException(
                    new IllegalArgumentException("Worker reply has a different probe version")));
        return false;
      }

      pending.result().complete(result);
      return true;
    }

    private void abandonProbe(UUID attemptId) {
      failProbe(attemptId, "Worker disconnected during probe dispatch");
    }

    private void failProbe(UUID attemptId, String reason) {
      var pending = activeProbes.remove(attemptId);
      if (pending == null) {
        return;
      }

      pending
          .result()
          .completeExceptionally(new ProbeExecutionException(new IllegalStateException(reason)));
      pendingProbes.remove(attemptId, pending.result());
    }

    private boolean canAccessSource(VariantJob job) {
      return job.hasSource() && canAccessSourceNamespace(job.getSource().getSourceNamespaceId());
    }

    private boolean canAccessSourceNamespace(Uuid sourceNamespaceId) {
      return sourceNamespaceIds.contains(sourceNamespaceId);
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

    private synchronized boolean stopVariant(UUID streamSessionId, String variantLabel) {
      return activeVariants.entrySet().stream()
          .filter(
              entry ->
                  fromProto(entry.getValue().getStreamSessionId()).equals(streamSessionId)
                      && entry.getValue().getVariant().getVariantLabel().equals(variantLabel))
          .map(Map.Entry::getKey)
          .findFirst()
          .map(this::tryStop)
          .orElse(false);
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
      return Math.max(0, maximumActiveVariants - activeVariants.size() - activeProbes.size());
    }

    private synchronized void stopStreamSession(UUID streamSessionId) {
      activeVariants.entrySet().stream()
          .filter(entry -> fromProto(entry.getValue().getStreamSessionId()).equals(streamSessionId))
          .map(Map.Entry::getKey)
          .toList()
          .forEach(this::tryStop);
    }

    private synchronized boolean isRunning(UUID streamSessionId, String variantLabel) {
      return activeVariants.values().stream()
          .anyMatch(
              job ->
                  fromProto(job.getStreamSessionId()).equals(streamSessionId)
                      && job.getVariant().getVariantLabel().equals(variantLabel));
    }

    private synchronized boolean authorizesUpload(SegmentUploadMetadata metadata) {
      return isFromThisWorkerSession(metadata) && matchesAnActiveJob(metadata);
    }

    private boolean isFromThisWorkerSession(SegmentUploadMetadata metadata) {
      return toProto(workerSessionId).equals(metadata.getWorkerSessionId())
          && worker.equals(metadata.getWorker());
    }

    private boolean matchesAnActiveJob(SegmentUploadMetadata metadata) {
      var job = activeVariants.get(fromProto(metadata.getJobAttemptId()));
      if (job == null) {
        return false;
      }
      return job.getStreamSessionId().equals(metadata.getStreamSessionId())
          && job.getJobId().equals(metadata.getJobId())
          && job.getVariant().getVariantLabel().equals(metadata.getVariantLabel());
    }

    private synchronized boolean publishIfStillAuthorized(
        SegmentUploadMetadata metadata, Runnable publication) {
      if (!authorizesUpload(metadata)) {
        return false;
      }
      publication.run();
      return true;
    }

    /**
     * Takes no connection monitor: a publish holds it across a filesystem move, and a disconnect
     * must not wait for that. This is also why {@code activeVariants} is a {@code
     * ConcurrentHashMap}.
     */
    private List<VariantJob> abandonAllJobsWithoutWaiting() {
      var drained = List.copyOf(activeVariants.values());
      activeVariants.clear();
      activeProbes.keySet().forEach(attemptId -> failProbe(attemptId, "Worker session ended"));
      return drained;
    }

    private void closeAsReplaced() {
      synchronized (this) {
        try {
          responseObserver.onError(
              Status.ABORTED.withDescription("Worker connection replaced").asRuntimeException());
        } catch (RuntimeException _) {
          // The previous call is already dead; the replacement proceeds regardless.
        }
      }
    }

    private record PendingProbe(
        ProbeRequest request, CompletableFuture<ProbeAttemptResult> result) {}
  }
}
