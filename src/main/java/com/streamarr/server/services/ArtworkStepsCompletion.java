package com.streamarr.server.services;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Completes the scheduled task of an item's artwork steps in one transaction with the item's
 * artwork request, which it locks first. The task is removed only together with the request of the
 * attempted generation, or when no request remains. A newer generation runs at once and starts
 * without failures.
 */
@Builder
@RequiredArgsConstructor
public class ArtworkStepsCompletion {

  @NonNull private final ArtworkRequestRepository requests;
  @NonNull private final PlatformTransactionManager transactionManager;
  @NonNull private final TaskRepository artworkTasks;
  @NonNull private final Clock clock;

  /**
   * Returns how the attempt's execution completes. A finished generation removes the task. A step
   * left to retry waits for the longer of the image host's {@code Retry-After} and the capped
   * exponential backoff, and counts a failure. An interrupted attempt runs again at once without
   * counting a failure.
   */
  public CompletionHandler<ArtworkItem> handlerFor(ArtworkItem item, ArtworkStepsOutcome outcome) {
    return (complete, operations) ->
        inTransaction(() -> complete(item, outcome, complete.getExecution(), operations));
  }

  /**
   * Handles an execution that failed unexpectedly, for example because the database could not be
   * read: it waits for the capped exponential backoff and counts a failure.
   */
  public FailureHandler<ArtworkItem> failureHandler() {
    return (complete, operations) ->
        inTransaction(
            () -> {
              var item = (ArtworkItem) complete.getExecution().taskInstance.getData();
              if (requests.lockPendingGeneration(item).isEmpty()) {
                operations.remove();
                return;
              }

              retryAfterFailure(complete.getExecution(), Optional.empty());
            });
  }

  private void complete(
      ArtworkItem item,
      ArtworkStepsOutcome outcome,
      Execution execution,
      ExecutionOperations<ArtworkItem> operations) {
    if (outcome instanceof ArtworkStepsOutcome.Finished(var generation)) {
      completeGeneration(item, generation, execution, operations);
      return;
    }

    var pending = requests.lockPendingGeneration(item);
    if (pending.isEmpty()) {
      operations.remove();
      return;
    }

    var pendingGeneration = pending.get();
    switch (outcome) {
      case ArtworkStepsOutcome.Unfinished(var generation, var retryAfter)
          when generation == pendingGeneration ->
          retryAfterFailure(execution, retryAfter);
      case ArtworkStepsOutcome.Interrupted(var generation) when generation == pendingGeneration ->
          artworkTasks.reschedule(
              execution, RescheduleUpdate.toExecutionTime(clock.instant()).build());
      case ArtworkStepsOutcome.NotRequested _,
          ArtworkStepsOutcome.Finished _,
          ArtworkStepsOutcome.Unfinished _,
          ArtworkStepsOutcome.Interrupted _ ->
          runNewGeneration(execution);
    }
  }

  private void completeGeneration(
      ArtworkItem item,
      long generation,
      Execution execution,
      ExecutionOperations<ArtworkItem> operations) {
    if (requests.tryCompleteGeneration(item, generation)) {
      operations.remove();
      return;
    }

    runNewGeneration(execution);
  }

  private void runNewGeneration(Execution execution) {
    artworkTasks.reschedule(
        execution, RescheduleUpdate.toExecutionTime(clock.instant()).resetFailures().build());
  }

  private void retryAfterFailure(Execution execution, Optional<Duration> retryAfter) {
    var now = clock.instant();
    var failures = execution.consecutiveFailures;
    artworkTasks.reschedule(
        execution,
        RescheduleUpdate.toExecutionTime(now.plus(ArtworkFailure.retryDelay(failures, retryAfter)))
            .lastFailure(now)
            .consecutiveFailures(failures + 1)
            .build());
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(_ -> work.run());
  }
}
