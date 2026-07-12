package com.streamarr.server.services.streaming.worker.local;

import com.streamarr.server.services.streaming.source.MediaSourceCatalog;
import com.streamarr.server.services.streaming.source.MediaSourceUnavailableException;
import com.streamarr.server.services.streaming.worker.InspectJobQuery;
import com.streamarr.server.services.streaming.worker.InspectJobRejection;
import com.streamarr.server.services.streaming.worker.InspectJobResult;
import com.streamarr.server.services.streaming.worker.StartJobCommand;
import com.streamarr.server.services.streaming.worker.StartJobRejection;
import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.server.services.streaming.worker.StopJobCommand;
import com.streamarr.server.services.streaming.worker.StopJobRejection;
import com.streamarr.server.services.streaming.worker.StopJobResult;
import com.streamarr.server.services.streaming.worker.TranscodeWorkerPort;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import com.streamarr.transcode.engine.job.LocalTranscodeEngine;
import com.streamarr.transcode.engine.job.TranscodeEngineException;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LocalTranscodeWorkerAdapter implements TranscodeWorkerPort {

  private final WorkerTarget target;
  private final MediaSourceCatalog sourceCatalog;
  private final LocalTranscodeEngine engine;
  private final ConcurrentHashMap<UUID, CommandExecution> commands = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, JobLedger> jobLedgers = new ConcurrentHashMap<>();

  @Override
  public StartJobResult start(StartJobCommand command) {
    if (!target.equals(command.target())) {
      return new StartJobResult.Rejected(StartJobRejection.TARGET_MISMATCH);
    }
    var intent = new StartIntent(command.target(), command.specification());
    var claim = claim(command.commandId(), intent);
    if (!claim.execution().intent().equals(intent)) {
      return new StartJobResult.Rejected(StartJobRejection.COMMAND_CONFLICT);
    }
    if (claim.owner()) {
      completeStart(claim.execution(), command.specification());
    }
    return await(claim.execution().result(), StartJobResult.class);
  }

  @Override
  public StopJobResult stop(StopJobCommand command) {
    if (!target.equals(command.target())) {
      return new StopJobResult.Rejected(StopJobRejection.TARGET_MISMATCH);
    }
    var intent = new StopIntent(command.target(), command.jobRef());
    var claim = claim(command.commandId(), intent);
    if (!claim.execution().intent().equals(intent)) {
      return new StopJobResult.Rejected(StopJobRejection.COMMAND_CONFLICT);
    }
    if (claim.owner()) {
      completeStop(claim.execution(), command.jobRef());
    }
    return await(claim.execution().result(), StopJobResult.class);
  }

  @Override
  public InspectJobResult inspect(InspectJobQuery query) {
    if (!target.equals(query.target())) {
      return new InspectJobResult.Rejected(InspectJobRejection.TARGET_MISMATCH);
    }
    try {
      return new InspectJobResult.Observed(engine.inspect(query.jobRef()));
    } catch (TranscodeEngineException exception) {
      if (exception.reason() == TranscodeEngineException.Reason.CLEANUP_PENDING) {
        return new InspectJobResult.CleanupPending(query.jobRef());
      }
      throw exception;
    }
  }

  private CommandClaim claim(UUID commandId, CommandIntent intent) {
    var requested = new CommandExecution(intent);
    var retained = commands.putIfAbsent(commandId, requested);
    return retained == null ? new CommandClaim(requested, true) : new CommandClaim(retained, false);
  }

  private void completeStart(CommandExecution execution, TranscodeJobSpec specification) {
    var ledger = jobLedgers.computeIfAbsent(specification.jobRef().jobId(), _ -> new JobLedger());
    switch (ledger.prepareStart(specification)) {
      case StartPreparation.Rejected(var result) -> execution.result().complete(result);
      case StartPreparation.Join(var inFlight) -> completeJoinedStart(execution, inFlight);
      case StartPreparation.Execute(var inFlight, var resolvedSource) ->
          executeStart(new StartOperation(execution, ledger, inFlight), resolvedSource);
    }
  }

  private void completeJoinedStart(CommandExecution execution, InFlightStart inFlight) {
    try {
      execution.result().complete(await(inFlight.result(), StartJobResult.class));
    } catch (RuntimeException exception) {
      execution.result().completeExceptionally(exception);
    }
  }

  private void executeStart(StartOperation operation, Optional<Path> retainedSource) {
    try {
      var specification = operation.inFlight().specification();
      var source = retainedSource.orElseGet(() -> sourceCatalog.resolve(specification.source()));
      completeResolvedStart(operation, source);
    } catch (MediaSourceUnavailableException _) {
      operation.ledger().discard(operation.inFlight());
      completeStart(operation, new StartJobResult.Rejected(StartJobRejection.SOURCE_UNAVAILABLE));
    } catch (RuntimeException exception) {
      operation.ledger().discard(operation.inFlight());
      failStart(operation, exception);
    }
  }

  private void completeResolvedStart(StartOperation operation, Path source) {
    var specification = operation.inFlight().specification();
    try {
      var observation = engine.start(specification, source);
      var accepted = new StartJobResult.Accepted(observation);
      operation.ledger().recordAccepted(operation.inFlight(), source);
      completeStart(operation, accepted);
    } catch (TranscodeEngineException exception) {
      operation.ledger().recordEngineFailure(operation.inFlight(), source, exception.reason());
      completeStart(operation, mapStartFailure(exception, specification));
    } catch (RuntimeException exception) {
      operation.ledger().discard(operation.inFlight());
      failStart(operation, exception);
    }
  }

  private static void completeStart(StartOperation operation, StartJobResult result) {
    operation.inFlight().result().complete(result);
    operation.execution().result().complete(result);
  }

  private static void failStart(StartOperation operation, RuntimeException exception) {
    operation.inFlight().result().completeExceptionally(exception);
    operation.execution().result().completeExceptionally(exception);
  }

  private void completeStop(CommandExecution execution, TranscodeJobRef jobRef) {
    var ledger = jobLedgers.computeIfAbsent(jobRef.jobId(), _ -> new JobLedger());
    ledger.recordStop(jobRef);
    try {
      execution.result().complete(stop(jobRef));
    } catch (RuntimeException exception) {
      execution.result().completeExceptionally(exception);
    }
  }

  private static StartJobResult mapStartFailure(
      TranscodeEngineException exception, TranscodeJobSpec specification) {
    return switch (exception.reason()) {
      case STALE_GENERATION -> new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION);
      case JOB_CONFLICT, SESSION_CONFLICT ->
          new StartJobResult.Rejected(StartJobRejection.JOB_CONFLICT);
      case INVALID_SPECIFICATION ->
          new StartJobResult.Rejected(StartJobRejection.INVALID_SPECIFICATION);
      case STARTUP_FAILED -> new StartJobResult.Rejected(StartJobRejection.STARTUP_FAILED);
      case SHUTTING_DOWN -> new StartJobResult.Rejected(StartJobRejection.SHUTTING_DOWN);
      case CLEANUP_PENDING -> new StartJobResult.CleanupPending(specification.jobRef());
    };
  }

  private StopJobResult stop(TranscodeJobRef jobRef) {
    try {
      var observation = engine.stop(jobRef);
      if (!engine.releaseObservation(jobRef)) {
        return new StopJobResult.CleanupPending(jobRef);
      }
      if (observation.state() == TranscodeJobState.ABSENT) {
        return new StopJobResult.AlreadyAbsent(jobRef);
      }
      return new StopJobResult.Stopped(jobRef);
    } catch (TranscodeEngineException _) {
      return new StopJobResult.CleanupPending(jobRef);
    }
  }

  private static <T> T await(CompletableFuture<?> result, Class<T> resultType) {
    try {
      return resultType.cast(result.join());
    } catch (CompletionException exception) {
      throw (RuntimeException) exception.getCause();
    }
  }

  private sealed interface CommandIntent permits StartIntent, StopIntent {}

  private record StartIntent(WorkerTarget target, TranscodeJobSpec specification)
      implements CommandIntent {}

  private record StopIntent(WorkerTarget target, TranscodeJobRef jobRef) implements CommandIntent {}

  private record CommandExecution(CommandIntent intent, CompletableFuture<Object> result) {

    private CommandExecution(CommandIntent intent) {
      this(intent, new CompletableFuture<>());
    }
  }

  private record CommandClaim(CommandExecution execution, boolean owner) {}

  private record StartOperation(
      CommandExecution execution, JobLedger ledger, InFlightStart inFlight) {}

  private static final class JobLedger {

    private final Map<Long, InFlightStart> inFlightStarts = new HashMap<>();
    private long highestStoppedGeneration;
    private long highestAdmittedGeneration;
    private RetainedAttempt retainedAttempt;

    private synchronized StartPreparation prepareStart(TranscodeJobSpec specification) {
      var generation = specification.jobRef().generation();
      if (generation <= highestStoppedGeneration || generation < highestAdmittedGeneration) {
        return new StartPreparation.Rejected(
            new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
      }
      var inFlight = inFlightStarts.get(generation);
      if (inFlight != null) {
        if (!inFlight.specification().equals(specification)) {
          return new StartPreparation.Rejected(
              new StartJobResult.Rejected(StartJobRejection.JOB_CONFLICT));
        }
        return new StartPreparation.Join(inFlight);
      }
      Optional<Path> resolvedSource = Optional.empty();
      if (retainedAttempt != null
          && retainedAttempt.specification().jobRef().generation() == generation) {
        if (!retainedAttempt.specification().equals(specification)) {
          return new StartPreparation.Rejected(
              new StartJobResult.Rejected(StartJobRejection.JOB_CONFLICT));
        }
        resolvedSource = Optional.of(retainedAttempt.resolvedSource());
      }
      var started = new InFlightStart(specification);
      inFlightStarts.put(generation, started);
      return new StartPreparation.Execute(started, resolvedSource);
    }

    private synchronized void recordAccepted(InFlightStart inFlight, Path resolvedSource) {
      discard(inFlight);
      recordAdmittedAttempt(inFlight.specification(), resolvedSource);
    }

    private synchronized void recordEngineFailure(
        InFlightStart inFlight, Path resolvedSource, TranscodeEngineException.Reason reason) {
      discard(inFlight);
      var generation = inFlight.specification().jobRef().generation();
      switch (reason) {
        case STALE_GENERATION -> {
          fenceThrough(generation);
          advanceTo(generation);
        }
        case STARTUP_FAILED, CLEANUP_PENDING ->
            recordAdmittedAttempt(inFlight.specification(), resolvedSource);
        case JOB_CONFLICT, SESSION_CONFLICT, INVALID_SPECIFICATION, SHUTTING_DOWN -> {
          // These failures admit no work, so discarding the in-flight marker is sufficient.
        }
      }
    }

    private synchronized void discard(InFlightStart inFlight) {
      inFlightStarts.remove(inFlight.specification().jobRef().generation(), inFlight);
    }

    private synchronized void recordStop(TranscodeJobRef jobRef) {
      fenceThrough(jobRef.generation());
    }

    private void recordAdmittedAttempt(TranscodeJobSpec specification, Path resolvedSource) {
      var generation = specification.jobRef().generation();
      advanceTo(generation);
      if (generation <= highestStoppedGeneration || generation < highestAdmittedGeneration) {
        return;
      }
      retainedAttempt = new RetainedAttempt(specification, resolvedSource);
    }

    private void fenceThrough(long generation) {
      highestStoppedGeneration = Math.max(highestStoppedGeneration, generation);
      if (retainedAttempt != null
          && retainedAttempt.specification().jobRef().generation() <= highestStoppedGeneration) {
        retainedAttempt = null;
      }
    }

    private void advanceTo(long generation) {
      highestAdmittedGeneration = Math.max(highestAdmittedGeneration, generation);
      if (retainedAttempt != null
          && retainedAttempt.specification().jobRef().generation() < highestAdmittedGeneration) {
        retainedAttempt = null;
      }
    }
  }

  private sealed interface StartPreparation {

    record Rejected(StartJobResult result) implements StartPreparation {}

    record Join(InFlightStart inFlight) implements StartPreparation {}

    record Execute(InFlightStart inFlight, Optional<Path> resolvedSource)
        implements StartPreparation {}
  }

  private record InFlightStart(
      TranscodeJobSpec specification, CompletableFuture<StartJobResult> result) {

    private InFlightStart(TranscodeJobSpec specification) {
      this(specification, new CompletableFuture<>());
    }
  }

  private record RetainedAttempt(TranscodeJobSpec specification, Path resolvedSource) {}
}
