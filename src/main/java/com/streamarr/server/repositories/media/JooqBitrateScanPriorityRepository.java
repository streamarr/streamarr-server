package com.streamarr.server.repositories.media;

import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.services.library.BitrateScanPriority;
import com.streamarr.server.services.library.BitrateScanTask;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqBitrateScanPriorityRepository implements BitrateScanPriorityRepository {

  private final ScheduledExecutionPriority priorities;

  public JooqBitrateScanPriorityRepository(DSLContext dsl) {
    priorities = new ScheduledExecutionPriority(dsl, KeyframePacketScanScheduler.TABLE_NAME);
  }

  @Override
  public boolean tryRaisePriority(UUID mediaFileId, BitrateScanPriority priority) {
    return priorities.tryRaise(
        TaskInstanceId.of(BitrateScanTask.NAME, mediaFileId.toString()), priority.value());
  }
}
