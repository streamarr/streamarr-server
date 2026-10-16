package com.streamarr.server.repositories.media;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// db-scheduler offers no call that raises a pending execution's priority. Its own reschedule
// keeps the priority column, and deleting and inserting the execution would reset its backoff.
@Repository
@RequiredArgsConstructor
public class JooqProbeTaskPriorityRepository implements ProbeTaskPriorityRepository {

  private static final Field<String> TASK_NAME = DSL.field(DSL.name("task_name"), String.class);
  private static final Field<String> TASK_INSTANCE =
      DSL.field(DSL.name("task_instance"), String.class);
  private static final Field<Boolean> PICKED = DSL.field(DSL.name("picked"), Boolean.class);
  private static final Field<Short> PRIORITY = DSL.field(DSL.name("priority"), Short.class);

  private final DSLContext dsl;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryRaisePendingPriority(TaskInstanceId instance, int priority) {
    var raised = (short) priority;
    return dsl.update(DSL.table(DSL.name(JdbcTaskRepository.DEFAULT_TABLE_NAME)))
            .set(PRIORITY, raised)
            .where(TASK_NAME.eq(instance.getTaskName()))
            .and(TASK_INSTANCE.eq(instance.getId()))
            .and(PICKED.isFalse())
            .and(PRIORITY.isNull().or(PRIORITY.lt(raised)))
            .execute()
        == 1;
  }
}
