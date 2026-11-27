package com.streamarr.server.repositories.media;

import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

// db-scheduler offers no call that raises a pending execution's priority. Its own reschedule
// keeps the priority column, and deleting and inserting the execution would reset its backoff.
@RequiredArgsConstructor
final class ScheduledExecutionPriority {

  private static final Field<String> TASK_NAME = DSL.field(DSL.name("task_name"), String.class);
  private static final Field<String> TASK_INSTANCE =
      DSL.field(DSL.name("task_instance"), String.class);
  private static final Field<Short> PRIORITY = DSL.field(DSL.name("priority"), Short.class);

  private final DSLContext dsl;
  private final String schedulerTable;

  /**
   * Raises the priority of an execution in the scheduler's table with one statement. Returns {@code
   * false} without writing when the execution is missing or already has at least that priority.
   */
  boolean tryRaise(TaskInstanceId instance, int priority) {
    var raised = (short) priority;
    return dsl.update(DSL.table(DSL.name(schedulerTable)))
            .set(PRIORITY, raised)
            .where(TASK_NAME.eq(instance.getTaskName()))
            .and(TASK_INSTANCE.eq(instance.getId()))
            .and(PRIORITY.lt(raised))
            .execute()
        == 1;
  }
}
