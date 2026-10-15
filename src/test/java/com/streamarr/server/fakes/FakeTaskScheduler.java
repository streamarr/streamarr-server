package com.streamarr.server.fakes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** Holds one-shot tasks until the test runs the ones that are due. */
public class FakeTaskScheduler implements TaskScheduler {

  private final List<ScheduledTask> tasks = new CopyOnWriteArrayList<>();

  /** Runs every task due at or before the instant that was not cancelled. */
  public void runTasksDueAt(Instant now) {
    for (var task : tasks) {
      if (!task.startTime.isAfter(now) && task.tryStart()) {
        task.runnable.run();
      }
    }
  }

  /** Tasks neither run nor cancelled. */
  public long pendingTaskCount() {
    return tasks.stream().filter(ScheduledTask::isPending).count();
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
    var scheduled = new ScheduledTask(task, startTime);
    tasks.add(scheduled);
    return scheduled;
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable task, Instant startTime, Duration delay) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
    throw new UnsupportedOperationException();
  }

  private static final class ScheduledTask implements ScheduledFuture<Object> {

    private final Runnable runnable;
    private final Instant startTime;
    private boolean started;
    private boolean cancelled;

    private ScheduledTask(Runnable runnable, Instant startTime) {
      this.runnable = runnable;
      this.startTime = startTime;
    }

    private synchronized boolean tryStart() {
      if (started || cancelled) {
        return false;
      }

      started = true;
      return true;
    }

    private synchronized boolean isPending() {
      return !started && !cancelled;
    }

    @Override
    public synchronized boolean cancel(boolean mayInterruptIfRunning) {
      if (started) {
        return false;
      }

      cancelled = true;
      return true;
    }

    @Override
    public synchronized boolean isCancelled() {
      return cancelled;
    }

    @Override
    public synchronized boolean isDone() {
      return started || cancelled;
    }

    @Override
    public Object get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Delayed other) {
      throw new UnsupportedOperationException();
    }
  }
}
