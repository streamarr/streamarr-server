package com.streamarr.server.support;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a task on its own virtual thread and waits for it with a bound. A task that outlives its
 * bound, or a wait that is interrupted, interrupts the task and waits a bound for its thread to
 * stop, so a stuck task fails its test instead of outliving it.
 */
public final class BoundedTask<T> implements AutoCloseable {

  private static final Duration CLOSE_BOUND = Duration.ofSeconds(5);

  private final Thread thread;
  private final CompletableFuture<T> result;

  private BoundedTask(Thread thread, CompletableFuture<T> result) {
    this.thread = thread;
    this.result = result;
  }

  public static <T> BoundedTask<T> start(Callable<T> task) {
    var result = new CompletableFuture<T>();
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    result.complete(task.call());
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                  }
                });
    return new BoundedTask<>(thread, result);
  }

  public static BoundedTask<Void> start(Action action) {
    return start(
        () -> {
          action.run();
          return null;
        });
  }

  public static void runWithin(Duration bound, Action action) throws Exception {
    try (var task = start(action)) {
      task.await(bound);
    }
  }

  /**
   * Returns the task's result or rethrows its own failure. The task gets the same bound again to
   * stop after its interrupt.
   */
  public T await(Duration bound) throws Exception {
    try {
      return result.get(bound.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException _) {
      stop(bound);
      throw new AssertionError("Task did not finish within " + bound);
    } catch (InterruptedException interrupted) {
      stop(bound);
      throw interrupted;
    } catch (ExecutionException failure) {
      throw switch (failure.getCause()) {
        case Error error -> throw error;
        case Exception exception -> exception;
        default -> failure;
      };
    }
  }

  @Override
  public void close() {
    stop(CLOSE_BOUND);
  }

  private void stop(Duration bound) {
    thread.interrupt();
    boolean stopped;
    try {
      stopped = thread.join(bound);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      stopped = !thread.isAlive();
    }

    if (!stopped) {
      throw new AssertionError("Task did not stop within " + bound + " of its interrupt");
    }
  }

  @FunctionalInterface
  public interface Action {

    void run() throws Exception;
  }
}
