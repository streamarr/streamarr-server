package com.streamarr.server.repositories.media;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqProbeTaskPriorityRepository implements ProbeTaskPriorityRepository {

  private final ScheduledExecutionPriority priorities;

  public JooqProbeTaskPriorityRepository(DSLContext dsl) {
    priorities = new ScheduledExecutionPriority(dsl, JdbcTaskRepository.DEFAULT_TABLE_NAME);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryRaisePendingPriority(TaskInstanceId instance, int priority) {
    return priorities.tryRaise(instance, priority);
  }
}
