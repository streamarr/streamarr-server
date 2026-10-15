package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import java.time.Clock;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * The db-scheduler task definition that runs library operations. Each operation has one scheduled
 * task, whose instance id and data are the operation's id. An execution that ends removes the task.
 * An execution that a shutdown interrupted is due again at once without counting a failure, so it
 * runs when a scheduler starts. An execution that throws runs again with backoff, and a crashed
 * server's execution runs again once db-scheduler finds it dead.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LibraryOperationTaskDefinition {

  public static final String NAME = "library-operation";

  public static Task<UUID> create(
      @NonNull LibraryOperationExecution execution, @NonNull Clock clock) {
    return Tasks.custom(NAME, UUID.class)
        .onFailure(failureHandler(clock))
        .execute((instance, _) -> completionFor(execution.execute(instance.getData()), clock));
  }

  /** The scheduled task of the library operation. */
  public static TaskInstance<UUID> instance(
      @NonNull Task<UUID> task, @NonNull UUID libraryOperationId) {
    return task.instance(libraryOperationId.toString(), libraryOperationId);
  }

  private static CompletionHandler<UUID> completionFor(
      LibraryOperationExecutionResult result, Clock clock) {
    return switch (result) {
      case LibraryOperationExecutionResult.Ended _ -> new CompletionHandler.OnCompleteRemove<>();
      case LibraryOperationExecutionResult.Interrupted _ ->
          (complete, operations) -> {
            clearInterrupt();
            operations.reschedule(complete, clock.instant());
          };
    };
  }

  private static FailureHandler<UUID> failureHandler(Clock clock) {
    var backoff = new CappedExponentialBackoff<UUID>(clock);
    return (complete, operations) -> {
      clearInterrupt();
      backoff.onFailure(complete, operations);
    };
  }

  // The connection pool refuses a connection to an interrupted thread, so the completion clears
  // the shutdown's interrupt before it saves the task's next state.
  private static void clearInterrupt() {
    Thread.interrupted();
  }
}
