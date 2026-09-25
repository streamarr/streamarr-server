package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

final class ScheduledProbeTasks {

  private ScheduledProbeTasks() {}

  /**
   * Moves the media file's pending probe five minutes out with the given failure history, as a long
   * backoff would, and returns the new execution time.
   */
  static Instant delay(DSLContext dsl, UUID mediaFileId, int consecutiveFailures) {
    var executionTime = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
    assertThat(
            dsl.update(DSL.table("scheduled_tasks"))
                .set(DSL.field("consecutive_failures", Integer.class), consecutiveFailures)
                .set(DSL.field("execution_time", Instant.class), executionTime)
                .where(DSL.field("task_instance", String.class).eq(mediaFileId.toString()))
                .execute())
        .isOne();
    return executionTime;
  }
}
