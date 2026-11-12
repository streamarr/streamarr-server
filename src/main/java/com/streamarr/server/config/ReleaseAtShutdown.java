package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.event.ExecutionChain;
import com.github.kagkarlsson.scheduler.event.ExecutionInterceptor;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Releases an execution that its scheduler's shutdown cut off, so the next server claims it at once
 * instead of waiting until db-scheduler finds it dead. A failure while the scheduler stops is taken
 * as the stop's doing: the release keeps the execution's due time, data and failure count, so the
 * interrupt counts as no failure of the job and starts no backoff (ADR 0032). A release that cannot
 * reach the database leaves the execution picked, and db-scheduler runs it again once it finds it
 * dead.
 */
@RequiredArgsConstructor
public final class ReleaseAtShutdown implements ExecutionInterceptor {

  // The table of the scheduler that runs the execution.
  @NonNull private final TaskRepository tasks;

  @Override
  public CompletionHandler<?> execute(
      TaskInstance<?> taskInstance, ExecutionContext executionContext, ExecutionChain chain) {
    try {
      return chain.proceed(taskInstance, executionContext);
    } catch (RuntimeException failure) {
      if (!executionContext.getSchedulerState().isShuttingDown()) {
        throw failure;
      }

      return (_, _) -> release(executionContext);
    }
  }

  // The connection pool refuses a connection to an interrupted thread, so the release clears the
  // shutdown's interrupt before it writes.
  private void release(ExecutionContext executionContext) {
    Thread.interrupted();
    tasks.unpickPickedBatch(List.of(executionContext.getExecution()));
  }
}
