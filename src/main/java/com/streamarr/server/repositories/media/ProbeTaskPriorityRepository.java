package com.streamarr.server.repositories.media;

import com.github.kagkarlsson.scheduler.task.TaskInstanceId;

@FunctionalInterface
public interface ProbeTaskPriorityRepository {

  /**
   * Raises the priority of a probe execution and keeps its execution time and failure history. A
   * running execution keeps the raised priority when db-scheduler reschedules it after the attempt.
   * Returns {@code false} without writing when the execution is missing or already has at least
   * that priority. Requires the caller's transaction.
   */
  boolean tryRaisePendingPriority(TaskInstanceId instance, int priority);
}
