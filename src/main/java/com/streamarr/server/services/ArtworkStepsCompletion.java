package com.streamarr.server.services;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.exceptions.ArtworkStepsInterruptedException;
import com.streamarr.server.exceptions.ArtworkStepsUnfinishedException;
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
 * artwork request. The task is removed only together with the request of the attempted generation;
 * a newer generation runs at once.
 */
@Builder
@RequiredArgsConstructor
public class ArtworkStepsCompletion {

  @NonNull private final ArtworkRequestRepository requests;
  @NonNull private final PlatformTransactionManager transactionManager;
  @NonNull private final TaskRepository artworkTasks;
  @NonNull private final Clock clock;

  /**
   * Returns the handler for an attempt that ended. An unfinished or interrupted attempt fails the
   * execution instead, so that {@link #failureHandler} decides when it runs again.
   */
  public CompletionHandler<ArtworkItem> handlerFor(ArtworkItem item, ArtworkStepsOutcome outcome) {
    return switch (outcome) {
      case ArtworkStepsOutcome.NotRequested _ ->
          (complete, operations) ->
              inTransaction(
                  () -> {
                    if (requests.lockPendingGeneration(item).isEmpty()) {
                      operations.remove();
                      return;
                    }

                    operations.reschedule(complete, clock.instant());
                  });
      case ArtworkStepsOutcome.Finished(var generation) ->
          (complete, operations) ->
              inTransaction(
                  () -> {
                    if (requests.tryCompleteGeneration(item, generation)) {
                      operations.remove();
                      return;
                    }

                    operations.reschedule(complete, clock.instant());
                  });
      case ArtworkStepsOutcome.Unfinished(var generation, var retryAfter) ->
          throw new ArtworkStepsUnfinishedException(generation, retryAfter);
      case ArtworkStepsOutcome.Interrupted(var generation) ->
          throw new ArtworkStepsInterruptedException(generation);
    };
  }

  /**
   * Handles a failed execution in one transaction. When no request is pending, it removes the
   * execution. When a newer generation replaced the attempted one, or the attempt was interrupted,
   * it runs again at once; only a newer generation clears the failure history. Otherwise it waits
   * for the longer of the image host's {@code Retry-After} and the capped exponential backoff.
   */
  public FailureHandler<ArtworkItem> failureHandler() {
    return (complete, operations) ->
        inTransaction(
            () -> {
              var pending = requests.lockPendingGeneration(itemOf(complete));
              if (pending.isEmpty()) {
                operations.remove();
                return;
              }

              var cause = complete.getCause().orElse(null);
              var execution = complete.getExecution();
              var now = clock.instant();
              switch (cause) {
                case ArtworkStepsUnfinishedException unfinished
                    when unfinished.getGeneration() != pending.get() ->
                    artworkTasks.reschedule(
                        execution, RescheduleUpdate.toExecutionTime(now).resetFailures().build());
                case ArtworkStepsInterruptedException interrupted
                    when interrupted.getGeneration() != pending.get() ->
                    artworkTasks.reschedule(
                        execution, RescheduleUpdate.toExecutionTime(now).resetFailures().build());
                case ArtworkStepsInterruptedException _ ->
                    artworkTasks.reschedule(
                        execution, RescheduleUpdate.toExecutionTime(now).build());
                case ArtworkStepsUnfinishedException unfinished ->
                    operations.reschedule(
                        complete, now.plus(retryDelay(complete, unfinished.getRetryAfter())));
                case null, default ->
                    operations.reschedule(
                        complete, now.plus(retryDelay(complete, Optional.empty())));
              }
            });
  }

  private static Duration retryDelay(ExecutionComplete complete, Optional<Duration> retryAfter) {
    return ArtworkFailure.retryDelay(complete.getExecution().consecutiveFailures, retryAfter);
  }

  // ArtworkStepsTask gives every execution this handler receives an ArtworkItem as its data.
  private static ArtworkItem itemOf(ExecutionComplete complete) {
    return (ArtworkItem) complete.getExecution().taskInstance.getData();
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(_ -> work.run());
  }
}
