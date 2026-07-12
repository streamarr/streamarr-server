package com.streamarr.server.services.streaming;

import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.worker.InspectJobQuery;
import com.streamarr.server.services.streaming.worker.InspectJobResult;
import com.streamarr.server.services.streaming.worker.StartJobCommand;
import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.server.services.streaming.worker.StopJobCommand;
import com.streamarr.server.services.streaming.worker.StopJobResult;
import com.streamarr.server.services.streaming.worker.TranscodeWorkerPort;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Builder;

public class DefaultPlaybackTranscodeJobService implements PlaybackTranscodeJobService {

  private final TranscodeWorkerPort worker;
  private final WorkerTarget workerTarget;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final MutexFactory<UUID> sessionMutexes;
  private final ConcurrentHashMap<TranscodeJobRef, TranscodeJobSpec> specificationsByJobRef =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<TranscodeJobRef, UUID> stopCommandIds = new ConcurrentHashMap<>();

  @Builder
  public DefaultPlaybackTranscodeJobService(
      TranscodeWorkerPort worker,
      WorkerTarget workerTarget,
      RuntimeStreamSessionRegistry runtimeRegistry,
      MutexFactory<UUID> sessionMutexes) {
    this.worker = worker;
    this.workerTarget = workerTarget;
    this.runtimeRegistry = runtimeRegistry;
    this.sessionMutexes = sessionMutexes;
  }

  @Override
  public TranscodeJobObservation start(StartPlaybackTranscodeJobCommand command) {
    var mutex = sessionMutexes.getMutex(command.sessionId());
    mutex.lock();
    try {
      return startLocked(command);
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public ActiveTranscodeJobInspection inspectActive(UUID sessionId) {
    var active = runtimeRegistry.activeTranscodeJobRef(sessionId);
    if (active.isEmpty()) {
      return inspectUnpublished(sessionId);
    }
    var jobRef = active.orElseThrow();
    var newerIssued = newestIssuedAfter(sessionId, jobRef);
    if (newerIssued.isPresent()) {
      return new ActiveTranscodeJobInspection.Unavailable(newerIssued.orElseThrow());
    }
    var specification = specificationsByJobRef.get(jobRef);
    if (specification == null) {
      return new ActiveTranscodeJobInspection.Unavailable(jobRef);
    }
    try {
      var result = worker.inspect(new InspectJobQuery(workerTarget, jobRef));
      if (result instanceof InspectJobResult.Observed(var observation)
          && jobRef.equals(observation.jobRef())) {
        newerIssued = newestIssuedAfter(sessionId, jobRef);
        if (newerIssued.isPresent()) {
          return new ActiveTranscodeJobInspection.Unavailable(newerIssued.orElseThrow());
        }
        return new ActiveTranscodeJobInspection.Observed(
            observation, specification.execution().startNumber());
      }
    } catch (RuntimeException _) {
      // Inspection is fail-closed: uncertainty must not authorize a replacement.
    }
    return new ActiveTranscodeJobInspection.Unavailable(jobRef);
  }

  private Optional<TranscodeJobRef> newestIssuedAfter(UUID sessionId, TranscodeJobRef active) {
    return runtimeRegistry.snapshotTranscodeJobRefs(sessionId).stream()
        .filter(jobRef -> jobRef.generation() > active.generation())
        .max(java.util.Comparator.comparingLong(TranscodeJobRef::generation));
  }

  private ActiveTranscodeJobInspection inspectUnpublished(UUID sessionId) {
    return runtimeRegistry.snapshotTranscodeJobRefs(sessionId).stream()
        .max(java.util.Comparator.comparingLong(TranscodeJobRef::generation))
        .<ActiveTranscodeJobInspection>map(ActiveTranscodeJobInspection.Unavailable::new)
        .orElseGet(ActiveTranscodeJobInspection.None::new);
  }

  @Override
  public RuntimeTranscodeCleanup suspend(UUID sessionId) {
    var mutex = sessionMutexes.getMutex(sessionId);
    mutex.lock();
    try {
      return drain(sessionId);
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public RuntimeTranscodeCleanup cleanupTerminal(UUID sessionId) {
    return drain(sessionId);
  }

  private RuntimeTranscodeCleanup drain(UUID sessionId) {
    stopAll(runtimeRegistry.snapshotTranscodeJobRefs(sessionId));
    runtimeRegistry.awaitTranscodeStarts(sessionId);
    stopAll(runtimeRegistry.snapshotTranscodeJobRefs(sessionId));
    return runtimeRegistry.snapshotTranscodeJobRefs(sessionId).isEmpty()
        ? RuntimeTranscodeCleanup.COMPLETE
        : RuntimeTranscodeCleanup.PENDING;
  }

  private void stopAll(List<TranscodeJobRef> jobRefs) {
    var ordered =
        jobRefs.stream()
            .sorted(java.util.Comparator.comparingLong(TranscodeJobRef::generation))
            .toList();
    for (var jobRef : ordered) {
      if (stopExact(jobRef)) {
        runtimeRegistry.markTranscodeJobReleased(jobRef);
        specificationsByJobRef.remove(jobRef);
        stopCommandIds.remove(jobRef);
      }
    }
  }

  private TranscodeJobObservation startLocked(StartPlaybackTranscodeJobCommand command) {
    var superseded = runtimeRegistry.snapshotTranscodeJobRefs(command.sessionId());
    var start =
        runtimeRegistry
            .beginTranscodeStart(command.sessionId())
            .orElseThrow(() -> new SessionNotFoundException(command.sessionId()));
    TranscodeJobSpec specification;
    try {
      specification = specification(command, start.jobRef());
    } catch (RuntimeException exception) {
      runtimeRegistry.finishRejectedTranscodeStart(start, true);
      throw exception;
    }
    specificationsByJobRef.put(start.jobRef(), specification);

    StartJobResult result;
    try {
      result =
          worker.start(
              StartJobCommand.builder()
                  .commandId(UUID.randomUUID())
                  .target(workerTarget)
                  .specification(specification)
                  .build());
    } catch (RuntimeException exception) {
      runtimeRegistry.abortTranscodeStart(start);
      throw exception;
    }

    if (!(result instanceof StartJobResult.Accepted accepted)) {
      settleRejected(start, result);
      throw new TranscodeJobStartException(start.jobRef(), result);
    }
    if (!isAccepted(start.jobRef(), accepted.observation())) {
      runtimeRegistry.abortTranscodeStart(start);
      throw new TranscodeJobStartException(start.jobRef(), result);
    }
    if (!runtimeRegistry.completeTranscodeStart(start)) {
      var stopped = stopExact(start.jobRef());
      runtimeRegistry.finishRejectedTranscodeStart(start, stopped);
      if (stopped) {
        specificationsByJobRef.remove(start.jobRef());
      }
      throw new SessionNotFoundException(command.sessionId());
    }

    cleanupSuperseded(superseded);
    return accepted.observation();
  }

  private void settleRejected(RuntimeTranscodeStart start, StartJobResult result) {
    if (result instanceof StartJobResult.Rejected rejected && provesAbsent(rejected)) {
      runtimeRegistry.finishRejectedTranscodeStart(start, true);
      specificationsByJobRef.remove(start.jobRef());
      return;
    }
    runtimeRegistry.abortTranscodeStart(start);
  }

  private static boolean provesAbsent(StartJobResult.Rejected rejected) {
    return switch (rejected.reason()) {
      case TARGET_MISMATCH,
          STALE_GENERATION,
          CAPACITY_EXHAUSTED,
          SOURCE_UNAVAILABLE,
          INVALID_SPECIFICATION,
          SHUTTING_DOWN ->
          true;
      case COMMAND_CONFLICT, JOB_CONFLICT, STARTUP_FAILED -> false;
    };
  }

  private static TranscodeJobSpec specification(
      StartPlaybackTranscodeJobCommand command, TranscodeJobRef jobRef) {
    return TranscodeJobSpec.builder()
        .sessionId(command.sessionId())
        .jobRef(jobRef)
        .source(command.source())
        .decision(command.decision())
        .execution(command.execution())
        .renditions(command.renditions())
        .build();
  }

  private static boolean isAccepted(
      TranscodeJobRef expectedJobRef, TranscodeJobObservation observation) {
    return expectedJobRef.equals(observation.jobRef())
        && (observation.state() == TranscodeJobState.RUNNING
            || observation.state() == TranscodeJobState.COMPLETED);
  }

  private void cleanupSuperseded(List<TranscodeJobRef> superseded) {
    stopAll(superseded);
  }

  private boolean stopExact(TranscodeJobRef jobRef) {
    try {
      var result =
          worker.stop(
              StopJobCommand.builder()
                  .commandId(stopCommandIds.computeIfAbsent(jobRef, _ -> UUID.randomUUID()))
                  .target(workerTarget)
                  .jobRef(jobRef)
                  .build());
      stopCommandIds.remove(jobRef);
      return provesAbsent(jobRef, result);
    } catch (RuntimeException _) {
      return false;
    }
  }

  private static boolean provesAbsent(TranscodeJobRef jobRef, StopJobResult result) {
    return switch (result) {
      case StopJobResult.Stopped(var stopped) -> jobRef.equals(stopped);
      case StopJobResult.AlreadyAbsent(var absent) -> jobRef.equals(absent);
      case StopJobResult.CleanupPending _, StopJobResult.Rejected _ -> false;
    };
  }
}
