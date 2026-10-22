package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.StartVariantCommand;
import com.streamarr.transcode.v1.StopVariantCommand;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
final class LiveWorkerConnectionRegistry {

  private static final String INITIALIZATION_SEGMENT_MISMATCH_METRIC =
      "streamarr.streaming.initialization_segment_mismatches";
  private static final String ENCODER_BACKEND_MISMATCH_METRIC =
      "streamarr.streaming.encoder_backend_mismatches";
  private static final Executor PROBE_DEADLINE_CALLBACKS =
      command -> Thread.ofVirtual().name("worker-probe-deadline").start(command);
  private final long probeTimeoutNanos;
  private final long keyframeScanTimeoutNanos;
  private final long probeCancellationTimeoutNanos;
  private final long startReportTimeoutNanos;
  private final Counter initializationSegmentMismatches;
  private final Counter encoderBackendMismatches;
  private final HeldSegmentRequests heldRequests;
  private final ApplicationEventPublisher eventPublisher;

  @Builder
  LiveWorkerConnectionRegistry(
      @NonNull WorkerSessionServerConfiguration configuration,
      @NonNull MeterRegistry meterRegistry,
      @NonNull HeldSegmentRequests heldRequests,
      @NonNull ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    this.heldRequests = heldRequests;
    probeTimeoutNanos = configuration.probeTimeout().toNanos();
    keyframeScanTimeoutNanos = configuration.keyframeScanTimeout().toNanos();
    probeCancellationTimeoutNanos = configuration.probeCancellationTimeout().toNanos();
    startReportTimeoutNanos = configuration.startReportTimeout().toNanos();
    initializationSegmentMismatches =
        Counter.builder(INITIALIZATION_SEGMENT_MISMATCH_METRIC)
            .description(
                "Initialization segment uploads refused because they differ from the one stored"
                    + " for the variant")
            .register(meterRegistry);
    encoderBackendMismatches =
        Counter.builder(ENCODER_BACKEND_MISMATCH_METRIC)
            .description(
                "Job attempts stopped because the worker reported an encoder backend other than"
                    + " the one pinned for the variant")
            .register(meterRegistry);
  }

  private final ConcurrentHashMap<UUID, WorkerConnection> connections = new ConcurrentHashMap<>();
  private final Map<UUID, TranscodeFailure> terminalFailures = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<ProbeAttemptReport>> pendingProbes =
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

  Optional<VariantDispatch> dispatch(VariantJob job) {
    return dispatchToFirst(job, _ -> true);
  }

  // Every later job attempt of a pinned variant runs only where its encoder backend runs.
  Optional<VariantDispatch> dispatch(VariantJob job, WorkerVerifiedEncoder pinnedEncoder) {
    return dispatchToFirst(job, connection -> connection.advertises(pinnedEncoder));
  }

  private Optional<VariantDispatch> dispatchToFirst(
      VariantJob job, Predicate<WorkerConnection> eligible) {
    for (var connection : connections.values().stream().filter(eligible).toList()) {
      var dispatched = tryDispatchUnlessDisconnected(connection, job);
      if (dispatched.isPresent()) {
        return dispatched;
      }
    }
    return Optional.empty();
  }

  ProbeDispatch dispatchProbe(ProbeRequest request) {
    var attemptId = request.getProbeAttemptId();
    if (request.getProbeVersion() == 0
        || !request.hasSource()
        || (attemptId.getMostSignificantBits() == 0 && attemptId.getLeastSignificantBits() == 0)) {
      return new ProbeDispatch.Refused(ProbeRefusal.INVALID_REQUEST);
    }

    var refusal = ProbeRefusal.NO_CONNECTED_WORKER;
    for (var connection : connections.values()) {
      switch (connection.tryDispatchProbe(request)) {
        case ProbeDispatch.Dispatched dispatched when connections.containsValue(connection) -> {
          return dispatched;
        }
        case ProbeDispatch.Dispatched _ -> {
          connection.abandonProbe(fromProto(attemptId));
          refusal = refusal.mostSpecific(ProbeRefusal.WORKER_UNREACHABLE);
        }
        case ProbeDispatch.Refused(var reason) -> refusal = refusal.mostSpecific(reason);
      }
    }

    return new ProbeDispatch.Refused(refusal);
  }

  boolean completeProbe(UUID workerId, UUID workerSessionId, ProbeAttemptResult result) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return false;
    }

    return connection.completeProbe(result);
  }

  boolean acceptKeyframePositionsPart(
      UUID workerId, UUID workerSessionId, ProbeKeyframePositionsPart part) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return false;
    }

    return connection.acceptKeyframePositionsPart(part);
  }

  Optional<VariantDispatch> dispatchTo(ExecutionTargetId target, VariantJob job) {
    return dispatchTo(target, job, _ -> true);
  }

  // The named target must advertise the pin too, whoever chose it.
  Optional<VariantDispatch> dispatchTo(
      ExecutionTargetId target, VariantJob job, WorkerVerifiedEncoder pinnedEncoder) {
    return dispatchTo(target, job, connection -> connection.advertises(pinnedEncoder));
  }

  private Optional<VariantDispatch> dispatchTo(
      ExecutionTargetId target, VariantJob job, Predicate<WorkerConnection> eligible) {
    return connections.values().stream()
        .filter(connection -> connection.executionTargetId().equals(target))
        .filter(eligible)
        .findFirst()
        .flatMap(connection -> tryDispatchUnlessDisconnected(connection, job));
  }

  /**
   * {@code tryDispatch} is synchronized, but a disconnect drains {@code activeVariants} without
   * that monitor, so the drain can land between the send and the put. The job would then be
   * orphaned: held by a dropped connection, never logged as abandoned.
   */
  private Optional<VariantDispatch> tryDispatchUnlessDisconnected(
      WorkerConnection connection, VariantJob job) {
    if (!connection.tryDispatch(job)) {
      return Optional.empty();
    }
    if (!connections.containsValue(connection)) {
      connection.releaseJobAttempt(fromProto(job.getJobAttemptId()));
      return Optional.empty();
    }
    return Optional.of(new VariantDispatch(connection.encoderFor(job)));
  }

  Set<ExecutionTargetId> eligibleWorkers(UUID sourceNamespaceId, TranscodeDecision decision) {
    return eligibleWorkers(sourceNamespaceId, connection -> connection.canEncode(decision));
  }

  Set<ExecutionTargetId> eligibleWorkers(
      UUID sourceNamespaceId, WorkerVerifiedEncoder pinnedEncoder) {
    return eligibleWorkers(sourceNamespaceId, connection -> connection.advertises(pinnedEncoder));
  }

  private Set<ExecutionTargetId> eligibleWorkers(
      UUID sourceNamespaceId, Predicate<WorkerConnection> eligible) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .filter(eligible)
        .map(WorkerConnection::executionTargetId)
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

  synchronized void destroyStreamSession(UUID streamSessionId) {
    try {
      stopStreamSession(streamSessionId);
    } finally {
      terminalFailures.remove(streamSessionId);
    }
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

  Set<String> verifiedVideoCodecFamilies(UUID sourceNamespaceId) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .flatMap(connection -> connection.countedEncoders.keySet().stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  boolean hasProbeWorker(UUID sourceNamespaceId, int probeVersion) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .anyMatch(
            connection ->
                connection.canAccessSourceNamespace(sourceNamespace)
                    && connection.probeVersions.contains(probeVersion));
  }

  Set<String> encodableVideoCodecFamilies(UUID sourceNamespaceId) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .filter(connection -> connection.availableSlots() > 0)
        .flatMap(connection -> connection.countedEncoders.keySet().stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean isCountedForEncodes(WorkerVerifiedEncoder encoder) {
    return encoder.backend().kind() == EncoderBackend.Kind.SOFTWARE || encoder.keyframeVerified();
  }

  private static boolean encodesVideo(TranscodeDecision decision) {
    return decision.getMode() == TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE
        || decision.getMode() == TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE;
  }

  int availableSlots(UUID sourceNamespaceId) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .mapToInt(WorkerConnection::availableSlots)
        .sum();
  }

  int availableSlots(UUID sourceNamespaceId, TranscodeDecision decision) {
    var sourceNamespace = toProto(sourceNamespaceId);
    return connections.values().stream()
        .filter(connection -> connection.canAccessSourceNamespace(sourceNamespace))
        .filter(connection -> connection.canEncode(decision))
        .mapToInt(WorkerConnection::availableSlots)
        .sum();
  }

  Optional<TranscodeFailure> terminalFailure(UUID streamSessionId) {
    return Optional.ofNullable(terminalFailures.get(streamSessionId));
  }

  synchronized void close() {
    var closed = List.copyOf(connections.values());
    connections.clear();
    closed.forEach(WorkerConnection::abandonAllJobsWithoutWaiting);
    terminalFailures.clear();
  }

  Optional<VariantJob> failJobAttempt(
      UUID workerId, UUID workerSessionId, JobAttemptFailed failed) {
    var released = acceptJobAttemptFailure(workerId, workerSessionId, failed);
    released
        .filter(job -> WorkerConnection.isCopyOutputMismatch(job, failed))
        .ifPresent(
            job ->
                eventPublisher.publishEvent(
                    new VideoCopyOutputMismatchEvent(
                        fromProto(job.getStreamSessionId()),
                        fromProto(job.getJobAttemptId()),
                        job.getVariant().getVariantLabel())));
    return released;
  }

  private synchronized Optional<VariantJob> acceptJobAttemptFailure(
      UUID workerId, UUID workerSessionId, JobAttemptFailed failed) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return Optional.empty();
    }

    return connection.failJobAttempt(failed);
  }

  Optional<VariantJob> releaseJobAttempt(UUID workerId, UUID workerSessionId, UUID jobAttemptId) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return Optional.empty();
    }
    return connection.releaseJobAttempt(jobAttemptId);
  }

  void checkReportedEncoderBackend(UUID workerId, UUID workerSessionId, JobAttemptStarted started) {
    var connection = connections.get(workerId);
    if (connection == null || !connection.workerSessionId().equals(workerSessionId)) {
      return;
    }

    connection.checkReportedEncoderBackend(started);
  }

  boolean authorizesUpload(UUID authenticatedWorkerId, SegmentUploadMetadata metadata) {
    var connection = connections.get(authenticatedWorkerId);
    return connection != null && connection.authorizesUpload(metadata);
  }

  /** Empty when the upload no longer belongs to an active job attempt of this connection. */
  Optional<SegmentPublication> publishIfAuthorized(
      UUID authenticatedWorkerId,
      SegmentUploadMetadata metadata,
      Supplier<SegmentPublication> publish) {
    // Unsynchronized on purpose: a segment publish renames a file, or reads and compares an
    // initialization segment, and must not queue behind worker register/disconnect. Stale lookups
    // fail the connection's re-check.
    var connection = connections.get(authenticatedWorkerId);
    if (connection == null || !connection.awaitConfirmedEncoderBackend(metadata)) {
      return Optional.empty();
    }

    var outcome = connection.publishIfStillAuthorized(metadata, publish);
    outcome.ifPresent(
        _ ->
            heldRequests.wakeVariant(
                fromProto(metadata.getStreamSessionId()), metadata.getVariantLabel()));
    return outcome;
  }

  private final class WorkerConnection {

    private final UUID workerSessionId;
    private final WorkerIdentity worker;
    private final Set<Uuid> sourceNamespaceIds;
    private final Set<Integer> probeVersions;
    // The worker-verified encoder that counts for each codec family, keyed by family name. A start
    // report that names another backend withdraws the family's entry for this worker session.
    private final Map<String, WorkerVerifiedEncoder> countedEncoders;
    private final int maximumActiveVariants;
    private final StreamObserver<EstablishWorkerSessionResponse> responseObserver;

    private final Map<UUID, VariantJob> activeVariants = new ConcurrentHashMap<>();
    // Each active encode, keyed by job attempt id, with the encoder it was dispatched for. Its
    // start report must name that encoder's backend before any of its uploads publishes.
    private final Map<UUID, EncodeAttempt> encodeAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, PendingProbe> activeProbes = new ConcurrentHashMap<>();

    private WorkerConnection(
        UUID workerSessionId,
        WorkerRegistration registration,
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      this.workerSessionId = workerSessionId;
      worker = registration.getWorker();
      sourceNamespaceIds = Set.copyOf(registration.getCapabilities().getSourceNamespaceIdsList());
      probeVersions = Set.copyOf(registration.getCapabilities().getProbeVersionsList());
      countedEncoders =
          new ConcurrentHashMap<>(
              registration.getCapabilities().getVideoEncodersList().stream()
                  .map(RemoteEncoderBackendMapper::workerVerifiedEncoder)
                  .flatMap(Optional::stream)
                  .filter(LiveWorkerConnectionRegistry::isCountedForEncodes)
                  .collect(
                      Collectors.toUnmodifiableMap(
                          encoder -> encoder.backend().codecFamily(), Function.identity())));
      maximumActiveVariants = registration.getAvailableSlots();
      this.responseObserver = responseObserver;
    }

    private UUID workerSessionId() {
      return workerSessionId;
    }

    private ExecutionTargetId executionTargetId() {
      return new ExecutionTargetId(workerSessionId.toString());
    }

    private synchronized void accept() {
      var accepted =
          WorkerSessionAccepted.newBuilder().setWorkerSessionId(toProto(workerSessionId));
      responseObserver.onNext(
          EstablishWorkerSessionResponse.newBuilder().setSessionAccepted(accepted).build());
    }

    private synchronized boolean tryDispatch(VariantJob job) {
      if (terminalFailures.containsKey(fromProto(job.getStreamSessionId()))
          || !canAccessSource(job)
          || !canEncode(job.getDecision())
          || availableSlots() == 0) {
        return false;
      }

      var command = StartVariantCommand.newBuilder().setTarget(worker).setJob(job).build();
      if (!trySend(EstablishWorkerSessionResponse.newBuilder().setStartVariant(command).build())) {
        return false;
      }
      var jobAttemptId = fromProto(job.getJobAttemptId());
      activeVariants.put(jobAttemptId, job);
      encoderFor(job)
          .ifPresent(
              encoder ->
                  encodeAttempts.put(
                      jobAttemptId, new EncodeAttempt(encoder, new CompletableFuture<>())));
      return true;
    }

    private synchronized ProbeDispatch tryDispatchProbe(ProbeRequest request) {
      if (!probeVersions.contains(request.getProbeVersion())
          || !canAccessSourceNamespace(request.getSource().getSourceNamespaceId())) {
        return new ProbeDispatch.Refused(ProbeRefusal.NO_COMPATIBLE_WORKER);
      }

      if (availableSlots() == 0) {
        return new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY);
      }

      var attemptId = fromProto(request.getProbeAttemptId());
      var pending =
          new PendingProbe(
              request,
              new CompletableFuture<>(),
              new CompletableFuture<>(),
              new ReceivedKeyframePositionsParts());
      if (pendingProbes.putIfAbsent(attemptId, pending.result()) != null) {
        return new ProbeDispatch.Refused(ProbeRefusal.ATTEMPT_IN_PROGRESS);
      }

      activeProbes.put(attemptId, pending);
      pending
          .result()
          .whenComplete(
              (_, failure) -> {
                if (pending.result().isCancelled()) {
                  cancelProbeIfRequested(pending);
                  return;
                }

                if (failure instanceof TimeoutException) {
                  // Never run transport callbacks on CompletableFuture's shared timeout thread.
                  PROBE_DEADLINE_CALLBACKS.execute(() -> cancelProbeIfRequested(pending));
                }
              });
      var command = StartProbeCommand.newBuilder().setTarget(worker).setRequest(request).build();
      if (!trySend(EstablishWorkerSessionResponse.newBuilder().setStartProbe(command).build())) {
        activeProbes.remove(attemptId, pending);
        pendingProbes.remove(attemptId, pending.result());
        return new ProbeDispatch.Refused(ProbeRefusal.WORKER_UNREACHABLE);
      }

      return new ProbeDispatch.Dispatched(
          pending.result().orTimeout(timeoutNanos(request), TimeUnit.NANOSECONDS));
    }

    private long timeoutNanos(ProbeRequest request) {
      if (request.getScanKeyframePackets()) {
        return keyframeScanTimeoutNanos;
      }

      return probeTimeoutNanos;
    }

    private void cancelProbeIfRequested(PendingProbe pending) {
      // Publication may hold the connection monitor. The recovery deadline must not wait for it.
      pending
          .terminated()
          .orTimeout(probeCancellationTimeoutNanos, TimeUnit.NANOSECONDS)
          .exceptionally(
              failure -> {
                PROBE_DEADLINE_CALLBACKS.execute(() -> fenceUnresponsiveProbe(pending));
                return null;
              });
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

    private void fenceUnresponsiveProbe(PendingProbe pending) {
      var attemptId = fromProto(pending.request().getProbeAttemptId());
      if (!activeProbes.remove(attemptId, pending)) {
        return;
      }

      pendingProbes.remove(attemptId, pending.result());
      if (!connections.remove(fromProto(worker.getWorkerId()), this)) {
        return;
      }

      log.warn("Worker session {} ignored cancellation of probe {}", workerSessionId, attemptId);
      abandonAllJobsWithoutWaiting()
          .forEach(job -> logAbandonedJob(job, "probe cancellation timed out"));
      synchronized (this) {
        try {
          responseObserver.onError(
              Status.DEADLINE_EXCEEDED
                  .withDescription("Worker did not stop a cancelled probe")
                  .asRuntimeException());
        } catch (RuntimeException failure) {
          log.debug(
              "Worker session {} already closed after probe timeout", workerSessionId, failure);
        }
      }
    }

    private boolean completeProbe(ProbeAttemptResult result) {
      var attemptId = fromProto(result.getProbeAttemptId());
      var pending = activeProbes.remove(attemptId);
      if (pending == null) {
        return false;
      }

      pending.terminated().complete(null);
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
                    ItemFailureReason.MISCONFIGURED,
                    "Worker probe reply version mismatch: expected %s, received %s"
                        .formatted(
                            Integer.toUnsignedString(pending.request().getProbeVersion()),
                            Integer.toUnsignedString(result.getProbeVersion()))));
        return false;
      }

      if (pending.keyframePositionsParts().violatesContract()) {
        pending
            .result()
            .completeExceptionally(
                new ProbeExecutionException(
                    ItemFailureReason.TEMPORARY,
                    "Worker probe sent keyframe positions parts that break the contract"));
        return false;
      }

      pending
          .result()
          .complete(new ProbeAttemptReport(result, pending.keyframePositionsParts().parts()));
      return true;
    }

    private boolean acceptKeyframePositionsPart(ProbeKeyframePositionsPart part) {
      var pending = activeProbes.get(fromProto(part.getProbeAttemptId()));
      if (pending == null) {
        return false;
      }

      pending.keyframePositionsParts().add(part);
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

      pending.terminated().complete(null);
      pending
          .result()
          .completeExceptionally(new ProbeExecutionException(ItemFailureReason.TEMPORARY, reason));
      pendingProbes.remove(attemptId, pending.result());
    }

    private boolean canAccessSource(VariantJob job) {
      return job.hasSource() && canAccessSourceNamespace(job.getSource().getSourceNamespaceId());
    }

    private boolean canEncode(TranscodeDecision decision) {
      return !encodesVideo(decision) || encoderFor(decision).isPresent();
    }

    private Optional<WorkerVerifiedEncoder> encoderFor(VariantJob job) {
      if (!encodesVideo(job.getDecision())) {
        return Optional.empty();
      }

      return encoderFor(job.getDecision());
    }

    private Optional<WorkerVerifiedEncoder> encoderFor(TranscodeDecision decision) {
      return RemoteEncoderBackendMapper.videoCodecFamilyName(decision.getVideo().getCodecFamily())
          .map(countedEncoders::get);
    }

    // The keyframe-verified flag must match too: a playlist that carries
    // EXT-X-INDEPENDENT-SEGMENTS for the pin needs closed GOPs from every attempt.
    private boolean advertises(WorkerVerifiedEncoder pinnedEncoder) {
      return pinnedEncoder.equals(countedEncoders.get(pinnedEncoder.backend().codecFamily()));
    }

    private boolean canAccessSourceNamespace(Uuid sourceNamespaceId) {
      return sourceNamespaceIds.contains(sourceNamespaceId);
    }

    private synchronized boolean tryStop(UUID jobAttemptId) {
      forgetEncodeAttempt(jobAttemptId);
      var stopped = activeVariants.remove(jobAttemptId);
      if (stopped == null) {
        return false;
      }

      wakeHeldRequests(stopped);

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

    private synchronized Optional<VariantJob> failJobAttempt(JobAttemptFailed failed) {
      var attemptId = fromProto(failed.getJobAttemptId());
      var job = activeVariants.get(attemptId);
      if (job == null) {
        return Optional.empty();
      }

      if (isCopyOutputMismatch(job, failed)) {
        terminalFailures.put(fromProto(job.getStreamSessionId()), TranscodeFailure.OUTPUT_MISMATCH);
      }

      return releaseJobAttempt(attemptId);
    }

    private static boolean isCopyOutputMismatch(VariantJob job, JobAttemptFailed failed) {
      if (failed.getFailure() != JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH) {
        return false;
      }

      return switch (job.getDecision().getMode()) {
        case TRANSCODE_MODE_REMUX, TRANSCODE_MODE_AUDIO_TRANSCODE -> true;
        default -> false;
      };
    }

    private synchronized Optional<VariantJob> releaseJobAttempt(UUID jobAttemptId) {
      forgetEncodeAttempt(jobAttemptId);
      var released = Optional.ofNullable(activeVariants.remove(jobAttemptId));
      released.ifPresent(this::wakeHeldRequests);
      return released;
    }

    // Wakes the requests held on the job's variant after the attempt leaves activeVariants, so a
    // woken request finds no running attempt.
    private void wakeHeldRequests(VariantJob job) {
      heldRequests.wakeVariant(
          fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
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

    private synchronized Optional<SegmentPublication> publishIfStillAuthorized(
        SegmentUploadMetadata metadata, Supplier<SegmentPublication> publish) {
      if (!authorizesUpload(metadata)) {
        return Optional.empty();
      }

      var outcome = publish.get();
      if (outcome == SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS) {
        refuseDifferingInitialization(metadata);
      }

      return Optional.of(outcome);
    }

    // Dispatch sends an encode only to a worker that advertises the variant's pinned backend, so
    // the encoder that the attempt was dispatched for is the pin.
    private synchronized void checkReportedEncoderBackend(JobAttemptStarted started) {
      var jobAttemptId = fromProto(started.getJobAttemptId());
      var job = activeVariants.get(jobAttemptId);
      if (job == null) {
        return;
      }

      var attempt = encodeAttempts.get(jobAttemptId);
      if (attempt == null) {
        return;
      }

      var pinned = attempt.encoder().backend();
      var reported =
          Optional.of(started)
              .filter(JobAttemptStarted::hasEncoderBackend)
              .map(JobAttemptStarted::getEncoderBackend)
              .flatMap(RemoteEncoderBackendMapper::encoderBackend);
      if (reported.equals(Optional.of(pinned))) {
        attempt.backendConfirmed().complete(true);
        return;
      }

      var stopped = tryStop(jobAttemptId);
      // The worker runs another encoder than it advertised, so no later attempt may rely on the
      // advertisement, including a replacement attempt of this variant.
      countedEncoders.remove(pinned.codecFamily());
      encoderBackendMismatches.increment();
      log.warn(
          "Stopped job attempt {} for stream session {} variant {} because the worker reported"
              + " encoder backend {} instead of the pinned {}; attempt stopped: {}",
          jobAttemptId,
          fromProto(job.getStreamSessionId()),
          job.getVariant().getVariantLabel(),
          reported.map(EncoderBackend::encoder).orElse("none"),
          pinned.encoder(),
          stopped);
    }

    // Unsynchronized: the start report that releases the wait takes this connection's monitor.
    // A copy, or an attempt this connection no longer runs, needs no wait; publication re-checks
    // ownership.
    private boolean awaitConfirmedEncoderBackend(SegmentUploadMetadata metadata) {
      var attempt = encodeAttempts.get(fromProto(metadata.getJobAttemptId()));
      if (attempt == null) {
        return true;
      }

      return attempt
          .backendConfirmed()
          .copy()
          .completeOnTimeout(false, startReportTimeoutNanos, TimeUnit.NANOSECONDS)
          .join();
    }

    private void forgetEncodeAttempt(UUID jobAttemptId) {
      var attempt = encodeAttempts.remove(jobAttemptId);
      if (attempt != null) {
        attempt.backendConfirmed().complete(false);
      }
    }

    private void refuseDifferingInitialization(SegmentUploadMetadata metadata) {
      // Stopping under the monitor that authorized this upload fences the attempt's later uploads,
      // so recovery finds no running producer and moves on. Workers upload an attempt's
      // initialization segment first and await its acknowledgement (ADR 0037), so none of its
      // media segments is published yet. A concurrent disconnect may already have ended it.
      var jobAttemptId = fromProto(metadata.getJobAttemptId());
      var stopped = tryStop(jobAttemptId);
      initializationSegmentMismatches.increment();
      log.warn(
          "Refused the initialization segment of job attempt {} for stream session {} variant {}"
              + " because it differs from the one stored for the variant; attempt stopped: {}",
          jobAttemptId,
          fromProto(metadata.getStreamSessionId()),
          metadata.getVariantLabel(),
          stopped);
    }

    /**
     * Takes no connection monitor: a publish holds it across file I/O (a rename, or reading and
     * comparing an initialization segment), and a disconnect must not wait for that. This is also
     * why {@code activeVariants} is a {@code ConcurrentHashMap}.
     */
    private List<VariantJob> abandonAllJobsWithoutWaiting() {
      var drained = List.copyOf(activeVariants.values());
      activeVariants.clear();
      drained.forEach(this::wakeHeldRequests);
      List.copyOf(encodeAttempts.keySet()).forEach(this::forgetEncodeAttempt);
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

    private record EncodeAttempt(
        WorkerVerifiedEncoder encoder, CompletableFuture<Boolean> backendConfirmed) {}

    private record PendingProbe(
        ProbeRequest request,
        CompletableFuture<ProbeAttemptReport> result,
        CompletableFuture<Void> terminated,
        ReceivedKeyframePositionsParts keyframePositionsParts) {}
  }
}
