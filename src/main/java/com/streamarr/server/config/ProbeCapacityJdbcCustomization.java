package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import lombok.NonNull;

/**
 * Lets the probe scheduler pick no more probe executions than the connected workers' free probe
 * capacity, so the scheduler never picks a probe that no worker can run yet. A probe left unpicked
 * stays pending without a failure, and the next poll picks it once a probe ends.
 *
 * <p>db-scheduler asks for its upper limit minus the executions it already runs, so the difference
 * counts the probe executions in progress, including those that have not reached a worker yet. When
 * no connected worker can run probes, the scheduler picks no probe, and every due probe waits until
 * a worker connects.
 *
 * <p>Picks from other tables, such as the keyframe packet scans' table, keep db-scheduler's limit.
 * Like {@link PostgreSqlJdbcCustomization}'s generic lock-and-fetch, the query selects the limited
 * rows first, and db-scheduler then updates exactly those rows.
 */
public final class ProbeCapacityJdbcCustomization extends PostgreSqlJdbcCustomization {

  private final ProbeCapacitySource capacity;
  private final int schedulerUpperLimit;

  /**
   * @param schedulerUpperLimit the probe scheduler's threads multiplied by its upper limit fraction
   *     of threads, the most executions it runs at once
   */
  public ProbeCapacityJdbcCustomization(
      @NonNull ProbeCapacitySource capacity, int schedulerUpperLimit) {
    super(true, false);
    this.capacity = capacity;
    this.schedulerUpperLimit = schedulerUpperLimit;
  }

  @Override
  public String createGenericSelectForUpdateQuery(
      String tableName, int limit, String requiredAndCondition, boolean orderByPriority) {
    return super.createGenericSelectForUpdateQuery(
        tableName, limitFor(tableName, limit), requiredAndCondition, orderByPriority);
  }

  private int limitFor(String tableName, int limit) {
    if (!JdbcTaskRepository.DEFAULT_TABLE_NAME.equals(tableName)) {
      return limit;
    }

    var executionsInProgress = schedulerUpperLimit - limit;
    return Math.min(limit, capacity.probeCapacity().free(executionsInProgress));
  }
}
