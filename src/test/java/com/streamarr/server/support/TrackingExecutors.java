package com.streamarr.server.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Supplies virtual-thread executors whose tasks a test can wait out: {@link #awaitQuiet} returns
 * once every task submitted to any executor it supplied has finished, so a test can assert that
 * work did not happen without waiting out a time window.
 */
public final class TrackingExecutors implements Supplier<ExecutorService> {

  private int unfinished;

  @Override
  public ExecutorService get() {
    return new TrackingExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  public synchronized void awaitQuiet(Duration bound) throws InterruptedException {
    var deadline = System.nanoTime() + bound.toNanos();
    while (unfinished > 0) {
      var remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new AssertionError(unfinished + " tasks were still running after " + bound);
      }

      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
  }

  private synchronized void started() {
    unfinished++;
  }

  private synchronized void finished() {
    unfinished--;
    notifyAll();
  }

  private final class TrackingExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;

    private TrackingExecutor(ExecutorService delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
      started();
      try {
        delegate.execute(
            () -> {
              try {
                task.run();
              } finally {
                finished();
              }
            });
      } catch (RuntimeException rejected) {
        finished();
        throw rejected;
      }
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
