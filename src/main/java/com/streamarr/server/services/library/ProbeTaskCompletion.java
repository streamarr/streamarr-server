package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeTaskRequest;
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
                  }
                });
  }

  private ProbeExecutionResult latestResult(ProbeTaskRequest request, ProbeExecutionResult result) {
    var desired = outcomes.lockProbeInputs(request.mediaFileId());
    if (desired.isEmpty()) {
      return new ProbeExecutionResult.Completed();
    }

    var inputs = desired.get();
    if (!inputs.equals(new ProbeInputs(request.snapshot(), request.probeVersion()))) {
      return retryWithRequestedInputs(
          result,
          request.toBuilder()
              .snapshot(inputs.snapshot())
              .probeVersion(inputs.probeVersion())
              .build());
    }

    nextInputs(result)
        .ifPresent(
            next ->
                outcomes.recordProbeRequest(
                    next.mediaFileId(), new ProbeInputs(next.snapshot(), next.probeVersion())));
    return result;
  }

  private static ProbeExecutionResult retryWithRequestedInputs(
      ProbeExecutionResult result, ProbeTaskRequest requested) {
    return switch (result) {
      case ProbeExecutionResult.SourceChanged _ ->
          new ProbeExecutionResult.SourceChanged(requested);
      case ProbeExecutionResult.Deferred _ -> new ProbeExecutionResult.Deferred(requested);
      case ProbeExecutionResult.Completed _, ProbeExecutionResult.Rescheduled _ ->
          new ProbeExecutionResult.Rescheduled(requested);
    };
  }

  private static Optional<ProbeTaskRequest> nextInputs(ProbeExecutionResult result) {
    return switch (result) {
      case ProbeExecutionResult.Completed _, ProbeExecutionResult.Deferred _ -> Optional.empty();
      case ProbeExecutionResult.Rescheduled(var next) -> Optional.of(next);
      case ProbeExecutionResult.SourceChanged(var next) -> Optional.of(next);
    };
  }

  private Duration quietPeriod() {
    return Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds());
  }
}
