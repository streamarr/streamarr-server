package com.streamarr.server.services;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
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

  /** The execution being completed, with the item it ran the steps of. */
  private record CompletingExecution(
      ArtworkItem item, Execution execution, ExecutionOperations<ArtworkItem> operations) {}

  /**
   * Returns how the attempt's execution completes. A finished generation removes the task. A step
   * left to retry waits for the longer of the image host's {@code Retry-After} and the capped
   * exponential backoff, and counts a failure. An interrupted attempt runs again at once without
   * counting a failure.
   */
  public CompletionHandler<ArtworkItem> handlerFor(ArtworkItem item, ArtworkStepsOutcome outcome) {
    return (complete, operations) ->
        inTransaction(
            () ->
                complete(
                    new CompletingExecution(item, complete.getExecution(), operations), outcome));
  }

  /**
   * Handles an execution that failed unexpectedly, for example because the database could not be
   * read: it waits for the capped exponential backoff and counts a failure.
   */
  public FailureHandler<ArtworkItem> failureHandler() {
    return (complete, operations) ->
        inTransaction(
            () -> {
              if (requests.lockPendingGeneration(artworkItemOf(complete)).isEmpty()) {
                operations.remove();
                return;
              }

              retryAfterFailure(complete.getExecution(), Optional.empty());
            });
  }

  // The task is created as a Task<ArtworkItem>, so every execution it completes carries an item.
  private static ArtworkItem artworkItemOf(ExecutionComplete complete) {
    return (ArtworkItem) complete.getExecution().taskInstance.getData();
  }

  private void complete(CompletingExecution completing, ArtworkStepsOutcome outcome) {
    switch (outcome) {
      case ArtworkStepsOutcome.Finished(var generation) ->
          completeGeneration(completing, generation);
      case ArtworkStepsOutcome.Unfinished(var generation, var retryAfter) ->
          actOnPendingGeneration(
              completing, generation, () -> retryAfterFailure(completing.execution(), retryAfter));
      case ArtworkStepsOutcome.Interrupted(var generation) ->
          actOnPendingGeneration(
              completing,
              generation,
              () ->
                  artworkTasks.reschedule(
                      completing.execution(),
                      RescheduleUpdate.toExecutionTime(clock.instant()).build()));
      case ArtworkStepsOutcome.NotRequested _ -> removeUnlessRequested(completing);
    }
  }

  private void completeGeneration(CompletingExecution completing, long generation) {
    if (requests.tryCompleteGeneration(completing.item(), generation)) {
      completing.operations().remove();
      return;
    }

    runNewGeneration(completing.execution());
  }

  // Acts on the attempt only while its generation is still pending: a newer generation runs at
  // once, and an item without a request has its task removed.
  private void actOnPendingGeneration(
      CompletingExecution completing, long attemptedGeneration, Runnable action) {
    var pending = requests.lockPendingGeneration(completing.item());
    if (pending.isEmpty()) {
      completing.operations().remove();
      return;
    }

    if (pending.get() == attemptedGeneration) {
      action.run();
      return;
    }

    runNewGeneration(completing.execution());
  }

  private void removeUnlessRequested(CompletingExecution completing) {
    if (requests.lockPendingGeneration(completing.item()).isEmpty()) {
      completing.operations().remove();
      return;
    }

    runNewGeneration(completing.execution());
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
