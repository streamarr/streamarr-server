package com.streamarr.server.fakes;

import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter;
import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.TaskSummary;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import com.github.kagkarlsson.scheduler.task.ScheduledTaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps the executions that a completion or failure handler removed or rescheduled. Handlers only
 * remove and reschedule, so every other operation is unsupported. Like the JDBC repository, it
 * turns each reschedule into a {@link RescheduleUpdate}.
 */
public final class FakeTaskRepository implements TaskRepository {

  private final List<Execution> removed = new ArrayList<>();
  private final List<RescheduleUpdate> rescheduled = new ArrayList<>();

  public synchronized List<Execution> removed() {
    return List.copyOf(removed);
  }

  public synchronized List<RescheduleUpdate> rescheduled() {
    return List.copyOf(rescheduled);
  }

  @Override
  public synchronized void remove(Execution execution) {
    removed.add(execution);
  }

  @Override
  public synchronized boolean reschedule(Execution execution, RescheduleUpdate rescheduleUpdate) {
    rescheduled.add(rescheduleUpdate);
    return true;
  }

  @Override
  public boolean createIfNotExists(SchedulableInstance<?> execution) {
    throw unsupported();
  }

  @Override
  public boolean createIfNotExists(ScheduledTaskInstance execution) {
    throw unsupported();
  }

  @Override
  public List<Execution> getDue(Instant now, int limit) {
    throw unsupported();
  }

  @Override
  public void createBatch(List<ScheduledTaskInstance> executions) {
    throw unsupported();
  }

  @Override
  public Instant replace(Execution toBeReplaced, ScheduledTaskInstance newInstance) {
    throw unsupported();
  }

  @Override
  public Instant replace(Execution toBeReplaced, SchedulableInstance<?> newInstance) {
    throw unsupported();
  }

  @Override
  public void getScheduledExecutions(
      ScheduledExecutionsFilter filter, Consumer<Execution> consumer) {
    throw unsupported();
  }

  @Override
  public void getScheduledExecutions(
      ScheduledExecutionsFilter filter, String taskName, Consumer<Execution> consumer) {
    throw unsupported();
  }

  @Override
  public List<TaskSummary> getScheduledExecutionsSummaryByTask() {
    throw unsupported();
  }

  @Override
  public List<Execution> lockAndFetchGeneric(Instant now, int limit) {
    throw unsupported();
  }

  @Override
  public List<Execution> lockAndGetDue(Instant now, int limit) {
    throw unsupported();
  }

  @Override
  public boolean reschedule(
      Execution execution,
      Instant nextExecutionTime,
      Instant lastSuccess,
      Instant lastFailure,
      int consecutiveFailures) {
    return reschedule(
        execution,
        RescheduleUpdate.toExecutionTime(nextExecutionTime)
            .lastSuccess(lastSuccess)
            .lastFailure(lastFailure)
            .consecutiveFailures(consecutiveFailures)
            .build());
  }

  @Override
  public boolean reschedule(
      Execution execution,
      Instant nextExecutionTime,
      Object newData,
      Instant lastSuccess,
      Instant lastFailure,
      int consecutiveFailures) {
    return reschedule(
        execution,
        RescheduleUpdate.toExecutionTime(nextExecutionTime)
            .data(newData)
            .lastSuccess(lastSuccess)
            .lastFailure(lastFailure)
            .consecutiveFailures(consecutiveFailures)
            .build());
  }

  @Override
  public Optional<Execution> pick(Execution e, Instant timePicked) {
    throw unsupported();
  }

  @Override
  public void unpickPickedBatch(List<Execution> pickedExecutions) {
    throw unsupported();
  }

  @Override
  public List<Execution> getDeadExecutions(Instant olderThan) {
    throw unsupported();
  }

  @Override
  public boolean updateHeartbeatWithRetry(Execution execution, Instant newHeartbeat, int tries) {
    throw unsupported();
  }

  @Override
  public boolean updateHeartbeat(Execution execution, Instant heartbeatTime) {
    throw unsupported();
  }

  @Override
  public List<Execution> getExecutionsFailingLongerThan(Duration interval) {
    throw unsupported();
  }

  @Override
  public Optional<Execution> getExecution(String taskName, String taskInstanceId) {
    throw unsupported();
  }

  @Override
  public int removeExecutions(String taskName) {
    throw unsupported();
  }

  @Override
  public void verifySupportsLockAndFetch() {
    throw unsupported();
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("Completion and failure handlers never call this");
  }
}
