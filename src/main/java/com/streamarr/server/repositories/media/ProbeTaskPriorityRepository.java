package com.streamarr.server.repositories.media;

import com.github.kagkarlsson.scheduler.task.TaskInstanceId;

@FunctionalInterface
public interface ProbeTaskPriorityRepository {

  /**
   * Raises the priority of a pending probe execution that db-scheduler has not picked, and keeps
   * its execution time and failure history. Returns {@code false} without writing when the
   * execution is running, is missing, or already has at least that priority. Requires the caller's
   * transaction.
   */
  boolean tryRaisePendingPriority(TaskInstanceId instance, int priority);
}
