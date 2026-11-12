package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.event.ExecutionChain;
import com.github.kagkarlsson.scheduler.event.ExecutionInterceptor;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Releases an execution that its scheduler's shutdown cut off, so the next server claims it at once
 * instead of waiting until db-scheduler finds it dead. An execution that the shutdown's interrupt
 * fails is released, and so is one whose completion fails while the scheduler stops. A release
 * keeps the execution's due time, data and failure count, so the interrupt counts as no failure of
 * the job and starts no backoff. Any other failure goes to the task's own failure handler. A
 * release that cannot reach the database leaves the execution picked, and db-scheduler runs it
 * again once it finds it dead.
 */
@Slf4j
@RequiredArgsConstructor
public final class ReleaseAtShutdown implements ExecutionInterceptor {

  // The table of the scheduler that runs the execution.
  @NonNull private final TaskRepository tasks;

  @Override
  public CompletionHandler<?> execute(
      TaskInstance<?> taskInstance, ExecutionContext executionContext, ExecutionChain chain) {
    CompletionHandler<?> completion;
    try {
      completion = chain.proceed(taskInstance, executionContext);
    } catch (RuntimeException failure) {
      if (!isShuttingDown(executionContext) || !isCausedByAnInterrupt(failure)) {
        throw failure;
      }

      return (_, _) -> release(executionContext);
    }

    return releasedIfItFailsAtShutdown(completion, executionContext);
  }

  // db-scheduler runs the completion after the task returned, outside this interceptor, so the
  // shutdown's interrupt can arrive before the completion begins or while it runs.
  private <T> CompletionHandler<T> releasedIfItFailsAtShutdown(
      CompletionHandler<T> completion, ExecutionContext executionContext) {
    return (complete, operations) -> {
      if (isShuttingDown(executionContext)) {
        clearTheShutdownsInterrupt();
      }

      try {
        completion.complete(complete, operations);
      } catch (RuntimeException failure) {
        if (!isShuttingDown(executionContext)) {
          throw failure;
        }

        log.warn(
            "Releasing {}, whose completion failed while its scheduler shut down",
            executionContext.getExecution(),
            failure);
        release(executionContext);
      }
    };
  }

  private void release(ExecutionContext executionContext) {
    clearTheShutdownsInterrupt();
    tasks.unpickPickedBatch(List.of(executionContext.getExecution()));
  }

  private static boolean isShuttingDown(ExecutionContext executionContext) {
    return executionContext.getSchedulerState().isShuttingDown();
  }

  // The interrupt either stays on the thread, as after a socket read on a virtual thread, or
  // travels as a cause, as when the connection pool stops waiting for a connection.
  private static boolean isCausedByAnInterrupt(RuntimeException failure) {
    return Thread.currentThread().isInterrupted()
        || ExceptionUtils.getThrowableList(failure).stream()
            .anyMatch(ReleaseAtShutdown::isAnInterrupt);
  }

  // A socket read that times out ends in an InterruptedIOException too, with no interrupt.
  private static boolean isAnInterrupt(Throwable cause) {
    return switch (cause) {
      case SocketTimeoutException _ -> false;
      case InterruptedException _, InterruptedIOException _, ClosedByInterruptException _ -> true;
      default -> false;
    };
  }

  // The connection pool refuses a connection to an interrupted thread.
  private static void clearTheShutdownsInterrupt() {
    Thread.interrupted();
  }
}
