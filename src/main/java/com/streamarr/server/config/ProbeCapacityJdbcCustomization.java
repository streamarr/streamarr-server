package com.streamarr.server.config;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.Priority;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/**
 * Lets the probe schedulers claim no more executions than the connected workers' free probe
 * capacities, so a scheduler never claims a probe that no worker can run yet. A probe left
 * unclaimed stays pending without a failure, and a later poll claims it once a unit frees.
 *
 * <p>The probe scheduler claims playback probes, which sort first by priority, up to the free
 * playback and background units, and other probes only up to the free background units. The
 * keyframe packet scan scheduler claims scans up to the free scan units. db-scheduler asks for its
 * upper limit minus the executions that it already runs, so the difference counts the executions in
 * progress, including those that have not reached a worker yet.
 *
 * <p>Claims from other tables keep db-scheduler's limit. Like {@link PostgreSqlJdbcCustomization}'s
 * generic lock-and-fetch, the query selects the limited rows first, and db-scheduler then updates
 * exactly those rows.
 */
public final class ProbeCapacityJdbcCustomization extends PostgreSqlJdbcCustomization {

  private static final String PROBE_TABLE = JdbcTaskRepository.DEFAULT_TABLE_NAME;
  private static final Field<String> TASK_NAME = field(name("task_name"), String.class);
  private static final Field<String> TASK_INSTANCE = field(name("task_instance"), String.class);
  private static final Field<Integer> PRIORITY = field(name("priority"), Integer.class);
  private static final Field<Boolean> PICKED = field(name("picked"), Boolean.class);
  private static final Field<Instant> EXECUTION_TIME = field(name("execution_time"), Instant.class);
  // Renders the claim condition with its values inline, so db-scheduler's bind parameters keep
  // their positions.
  private static final DSLContext RENDERER =
      DSL.using(SQLDialect.POSTGRES, new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER));

  private final ProbeCapacitySource capacitySource;
  private final int probeSchedulerUpperLimit;
  private final int scanSchedulerUpperLimit;

  /**
   * @param probeSchedulerUpperLimit the probe scheduler's threads multiplied by its upper limit
   *     fraction of threads, the most executions it runs at once
   * @param scanSchedulerUpperLimit the same for the keyframe packet scan scheduler
   */
  @Builder
  private ProbeCapacityJdbcCustomization(
      @NonNull ProbeCapacitySource capacitySource,
      int probeSchedulerUpperLimit,
      int scanSchedulerUpperLimit) {
    super(true, false);
    this.capacitySource = capacitySource;
    this.probeSchedulerUpperLimit = probeSchedulerUpperLimit;
    this.scanSchedulerUpperLimit = scanSchedulerUpperLimit;
  }

  @Override
  public String createGenericSelectForUpdateQuery(
      String tableName, int limit, String requiredAndCondition, boolean orderByPriority) {
    return switch (tableName) {
      case PROBE_TABLE -> probeClaim(limit, requiredAndCondition, orderByPriority);
      case KeyframePacketScanScheduler.TABLE_NAME -> {
        var scans =
            capacitySource
                .probeCapacity()
                .keyframePacketScansToClaim(scanSchedulerUpperLimit - limit);
        yield super.createGenericSelectForUpdateQuery(
            tableName, Math.min(limit, scans), requiredAndCondition, orderByPriority);
      }
      default ->
          super.createGenericSelectForUpdateQuery(
              tableName, limit, requiredAndCondition, orderByPriority);
    };
  }

  private String probeClaim(int limit, String requiredAndCondition, boolean orderByPriority) {
    var claim = capacitySource.probeCapacity().probesToClaim(probeSchedulerUpperLimit - limit);
    return super.createGenericSelectForUpdateQuery(
        PROBE_TABLE,
        Math.min(limit, claim.total()),
        requiredAndCondition
            + " AND "
            + RENDERER.renderInlined(oldestBackgroundProbesOnly(claim.backgroundProbes()))
            + " ",
        orderByPriority);
  }

  // Every probe that no playback request waits for must be among the oldest pending ones that the
  // free background units allow. Rows due later sort after every due row, so the oldest rows
  // include the due ones first.
  private static Condition oldestBackgroundProbesOnly(int backgroundProbes) {
    return PRIORITY
        .ge(Priority.HIGH)
        .or(
            row(TASK_NAME, TASK_INSTANCE)
                .in(
                    select(TASK_NAME, TASK_INSTANCE)
                        .from(table(name(PROBE_TABLE)))
                        .where(PICKED.isFalse().and(PRIORITY.lt(Priority.HIGH)))
                        .orderBy(EXECUTION_TIME.asc(), TASK_INSTANCE.asc())
                        .limit(backgroundProbes)));
  }
}
