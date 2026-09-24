package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Builder
@RequiredArgsConstructor
public class ProbeTaskCompletion {

  private final MediaFileContainerInfoRepository outcomes;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;
  private final ProbeSchedulingProperties properties;
  private final TaskRepository probeTasks;
  private final LibraryWatcherProperties watcherProperties;

  public CompletionHandler<ProbeTaskRequest> handlerFor(
      ProbeTaskRequest request, ProbeExecutionResult result) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                _ -> {
                  switch (latestResult(request, result)) {
                    case ProbeExecutionResult.Completed _ -> operations.remove();
                    case ProbeExecutionResult.Rescheduled(var next) ->
                        operations.reschedule(complete, clock.instant(), next);
                    case ProbeExecutionResult.Deferred(var next) ->
                        probeTasks.reschedule(
                            complete.getExecution(),
                            RescheduleUpdate.toExecutionTime(
                                    clock.instant().plus(properties.busyWorkerRetryDelay()))
                                .data(next)
                                .build());
                    case ProbeExecutionResult.SourceChanged(var next) ->
                        operations.reschedule(complete, clock.instant().plus(quietPeriod()), next);
                    case ProbeExecutionResult.SourceRemoved _ -> {
                      outcomes.withdrawProbeRequest(request.mediaFileId());
                      operations.remove();
                    }
                  }
                });
  }

  /**
   * Handles a failed attempt in one transaction. When a newer request replaced the attempted
   * inputs, it retries the requested inputs at once without recording a failure, because the
   * failure says nothing about them. Otherwise it records why the attempt failed and lets {@code
   * backoff} reschedule it. A cancelled attempt backs off without a recorded failure, and any other
   * exception counts as a temporary failure so that it does not wait unrecorded.
   */
  public FailureHandler<ProbeTaskRequest> failureHandler(FailureHandler<ProbeTaskRequest> backoff) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                _ -> {
                  var attempted = requestOf(complete);
                  var requested = newerRequest(attempted);
                  if (requested.isPresent()) {
                    operations.reschedule(complete, clock.instant(), requested.get());
                    return;
                  }

                  complete
                      .getCause()
                      .flatMap(ProbeTaskCompletion::failureOf)
                      .ifPresent(failure -> saveFailure(attempted, failure));
                  backoff.onFailure(complete, operations);
                });
  }

  private Optional<ProbeTaskRequest> newerRequest(ProbeTaskRequest attempted) {
    return outcomes
        .lockProbeInputs(attempted.mediaFileId())
        .filter(inputs -> !inputs.equals(attempted.inputs()))
        .map(inputs -> withInputs(attempted, inputs));
  }

  private void saveFailure(ProbeTaskRequest request, ItemOutcome.Failed failure) {
    outcomes.trySaveProbeFailure(
        request.mediaFileId(),
        request.inputs(),
        ProbeAttemptFailure.builder()
            .reason(failure.reason())
            .detail(failure.detail())
            .failedAt(clock.instant())
            .build());
  }

  private static Optional<ItemOutcome.Failed> failureOf(Throwable cause) {
    return switch (cause) {
      case ProbeCancelledException _ -> Optional.empty();
      case ProbeExecutionException failure ->
          Optional.of(new ItemOutcome.Failed(failure.reason(), failure.getMessage()));
      default ->
          Optional.of(
              new ItemOutcome.Failed(
                  ItemFailureReason.TEMPORARY, "Unexpected " + cause.getClass().getSimpleName()));
    };
  }

  // MediaProbeTask gives every execution this handler receives a ProbeTaskRequest as its data.
  private static ProbeTaskRequest requestOf(ExecutionComplete complete) {
    return (ProbeTaskRequest) complete.getExecution().taskInstance.getData();
  }

  private ProbeExecutionResult latestResult(ProbeTaskRequest request, ProbeExecutionResult result) {
    var desired = outcomes.lockProbeInputs(request.mediaFileId());
    if (desired.isEmpty()) {
      return new ProbeExecutionResult.Completed();
    }

    var inputs = desired.get();
    if (!inputs.equals(request.inputs())) {
      return retryWithRequestedInputs(result, withInputs(request, inputs));
    }

    nextInputs(result)
        .ifPresent(next -> outcomes.trySaveProbeRequest(next.mediaFileId(), next.inputs()));
    return result;
  }

  private static ProbeExecutionResult retryWithRequestedInputs(
      ProbeExecutionResult result, ProbeTaskRequest requested) {
    return switch (result) {
      case ProbeExecutionResult.SourceChanged _ ->
          new ProbeExecutionResult.SourceChanged(requested);
      case ProbeExecutionResult.Deferred _ -> new ProbeExecutionResult.Deferred(requested);
      case ProbeExecutionResult.Completed _,
          ProbeExecutionResult.Rescheduled _,
          ProbeExecutionResult.SourceRemoved _ ->
          new ProbeExecutionResult.Rescheduled(requested);
    };
  }

  private static ProbeTaskRequest withInputs(ProbeTaskRequest request, ProbeInputs inputs) {
    return request.toBuilder()
        .snapshot(inputs.snapshot())
        .probeVersion(inputs.probeVersion())
        .build();
  }

  private static Optional<ProbeTaskRequest> nextInputs(ProbeExecutionResult result) {
    return switch (result) {
      case ProbeExecutionResult.Completed _,
          ProbeExecutionResult.Deferred _,
          ProbeExecutionResult.SourceRemoved _ ->
          Optional.empty();
      case ProbeExecutionResult.Rescheduled(var next) -> Optional.of(next);
      case ProbeExecutionResult.SourceChanged(var next) -> Optional.of(next);
    };
  }

  private Duration quietPeriod() {
    return Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds());
  }
}
