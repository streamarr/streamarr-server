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
   * Records why an attempt at the requested inputs failed, in the transaction in which {@code
   * retry} reschedules it. A cancelled attempt retries without a recorded failure, and any other
   * exception counts as a temporary failure so that it does not wait unrecorded.
   */
  public FailureHandler<ProbeTaskRequest> recordingFailures(
      FailureHandler<ProbeTaskRequest> retry) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                _ -> {
                  complete
                      .getCause()
                      .flatMap(ProbeTaskCompletion::failureOf)
                      .ifPresent(failure -> recordFailure(complete, failure));
                  retry.onFailure(complete, operations);
                });
  }

  private void recordFailure(ExecutionComplete complete, ItemOutcome.Failed failure) {
    var request = requestOf(complete);
    outcomes.recordProbeFailure(
        request.mediaFileId(),
        request.inputs(),
        ProbeAttemptFailure.builder()
            .reason(failure.reason())
            .detail(failure.detail())
            .failedAt(complete.getTimeDone())
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
      return retryWithRequestedInputs(
          result,
          request.toBuilder()
              .snapshot(inputs.snapshot())
              .probeVersion(inputs.probeVersion())
              .build());
    }

    nextInputs(result)
        .ifPresent(next -> outcomes.recordProbeRequest(next.mediaFileId(), next.inputs()));
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
