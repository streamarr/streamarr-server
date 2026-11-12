package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.PollingStrategyConfig;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SystemClock;
import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.github.kagkarlsson.scheduler.jdbc.JdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.concurrency.NanoClock;
import com.streamarr.server.services.library.BitrateScanExecution;
import com.streamarr.server.services.library.BitrateScanTask;
import com.streamarr.server.services.library.KeyframePacketScanExecution;
import com.streamarr.server.services.library.MediaProbeTask;
import com.streamarr.server.services.library.ProbeExecution;
import com.streamarr.server.services.library.ProbeTaskCompletion;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class ProbeSchedulingConfiguration {

  @Bean
  public Serializer probeTaskSerializer() {
    return new JacksonTaskDataSerializer();
  }

  /**
   * Reads the probe scheduler's upper limit from the db-scheduler settings that the starter binds,
   * which also hold when the starter is off.
   */
  @Bean
  public ProbeCapacityJdbcCustomization probeCapacityJdbcCustomization(
      ProbeCapacitySource probeCapacitySource,
      PlaybackProbeClaims playbackProbeClaims,
      Environment environment) {
    var properties =
        Binder.get(environment)
            .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
            .orElseGet(DbSchedulerProperties::new);
    var probeProperties =
        Binder.get(environment).bindOrCreate("probe", ProbeSchedulingProperties.class);
    var pollingStrategy =
        new PollingStrategyConfig(
            properties.getPollingStrategy(),
            properties.getPollingStrategyLowerLimitFractionOfThreads(),
            properties.getPollingStrategyUpperLimitFractionOfThreads());
    return ProbeCapacityJdbcCustomization.builder()
        .capacitySource(probeCapacitySource)
        .playbackProbeClaims(playbackProbeClaims)
        .probeSchedulerUpperLimit(pollingStrategy.getUpperLimit(properties.getThreads()))
        .scanSchedulerUpperLimit(
            pollingStrategy.getUpperLimit(probeProperties.keyframePacketScanSchedulerThreads()))
        .build();
  }

  /**
   * Every db-scheduler built from this customizer shares the JDBC customization. Only the probe and
   * keyframe packet scan schedulers' claims follow the workers' free probe capacities.
   */
  @Bean
  public DbSchedulerCustomizer dbSchedulerCustomizer(
      Serializer probeTaskSerializer,
      DataSource dataSource,
      ProbeCapacityJdbcCustomization probeCapacityJdbcCustomization) {
    return new VirtualThreadCustomizer(
        probeTaskSerializer, dataSource, probeCapacityJdbcCustomization);
  }

  /**
   * The probe scheduler, built as the db-scheduler starter builds it, which then backs off. Its
   * executions that a shutdown cuts off are released for the next start, and {@link
   * ProbeSchedulerStop} stops it with the server's other background work instead of when the
   * context destroys it.
   */
  @Bean(destroyMethod = "")
  @ConditionalOnProperty(name = "db-scheduler.enabled", havingValue = "true", matchIfMissing = true)
  @DependsOnDatabaseInitialization
  public Scheduler probeScheduler(
      DbSchedulerProperties properties,
      DbSchedulerCustomizer dbSchedulerCustomizer,
      StatsRegistry statsRegistry,
      DataSource dataSource,
      List<Task<?>> tasks,
      List<SchedulerListener> listeners,
      TaskRepository probeTaskRepository) {
    return DbSchedulerConfigurationSupport.buildScheduler(
        properties,
        dbSchedulerCustomizer,
        statsRegistry,
        new SystemClock(),
        dataSource,
        tasks,
        listeners,
        List.of(new ReleaseAtShutdown(probeTaskRepository)));
  }

  @Bean
  @ConditionalOnProperty(name = "db-scheduler.enabled", havingValue = "true", matchIfMissing = true)
  public ProbeSchedulerStop probeSchedulerStop(Scheduler probeScheduler) {
    return new ProbeSchedulerStop(probeScheduler);
  }

  @Bean
  public Task<ProbeTaskRequest> mediaProbeTask(
      ProbeExecution execution, ProbeTaskCompletion completion, Clock clock) {
    return MediaProbeTask.create(execution, completion, clock);
  }

  /**
   * Runs keyframe packet scans on their own scheduler, which starts with the application only while
   * db-scheduler is enabled.
   */
  @Bean
  @SuppressWarnings("java:S107") // Spring injects each collaborator by type.
  public KeyframePacketScanScheduler keyframePacketScanScheduler(
      DbSchedulerCustomizer dbSchedulerCustomizer,
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      KeyframePacketScanExecution execution,
      BitrateScanExecution bitrateScanExecution,
      MediaFileContainerInfoRepository outcomes,
      BitrateScanRepository bitrateScans,
      MeterRegistry meterRegistry,
      ProbeSchedulingProperties properties,
      Clock clock,
      Environment environment) {
    var schedulerProperties =
        Binder.get(environment)
            .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
            .orElseGet(DbSchedulerProperties::new);
    return KeyframePacketScanScheduler.builder()
        .schedulerProperties(schedulerProperties)
        .customizer(dbSchedulerCustomizer)
        .dataSource(dataSource)
        .transactionManager(transactionManager)
        .execution(execution)
        .bitrateScanExecution(bitrateScanExecution)
        .outcomes(outcomes)
        .bitrateScans(bitrateScans)
        .meterRegistry(meterRegistry)
        .properties(properties)
        .clock(clock)
        .autoStartup(schedulerProperties.isEnabled())
        .build();
  }

  /** Reports how many bitrate scans wait in the keyframe packet scan table (ADR 0054). */
  @Bean
  public BitrateScansWaiting bitrateScansWaiting(
      DSLContext dsl, BitrateScansWaitingProperties properties, NanoClock nanoClock) {
    return BitrateScansWaiting.builder()
        .count(
            () ->
                dsl.fetchCount(
                    DSL.table(DSL.name(KeyframePacketScanScheduler.TABLE_NAME)),
                    DSL.field(DSL.name("task_name"), String.class)
                        .eq(BitrateScanTask.NAME)
                        .and(DSL.field(DSL.name("picked"), Boolean.class).isFalse())))
        .refreshInterval(properties.refreshInterval())
        .nanoClock(nanoClock)
        .build();
  }

  /**
   * Completes probe executions through the scheduler's own table and transaction. Unlike {@code
   * ExecutionOperations}, its selective reschedule can leave the failure history untouched.
   */
  @Bean
  public TaskRepository probeTaskRepository(DataSource dataSource, Serializer probeTaskSerializer) {
    return SchedulerTables.taskRepository()
        .dataSource(dataSource)
        .tableName(JdbcTaskRepository.DEFAULT_TABLE_NAME)
        .name("probe-task-completion")
        .serializer(probeTaskSerializer)
        .clock(Clock.systemUTC())
        .build();
  }

  @RequiredArgsConstructor
  private static final class VirtualThreadCustomizer implements DbSchedulerCustomizer {

    // As many threads as db-scheduler's own housekeeper, which runs the heartbeats and the check
    // for dead executions.
    private static final int HOUSEKEEPER_THREADS = 3;

    private final Serializer serializer;
    private final DataSource dataSource;
    private final JdbcCustomization jdbcCustomization;

    @Override
    public Optional<DataSource> dataSource() {
      return Optional.of(new SchedulerTransactionDataSource(dataSource));
    }

    // Every scheduler built from this customizer runs its executions, with their completion and
    // failure handlers, as the System actor. Each call returns a new executor, because a scheduler
    // shuts down its executor when it stops.
    @Override
    public Optional<ExecutorService> executorService() {
      return Optional.of(SystemWork.newExecutorRunningAsSystemActor("scheduled-task-"));
    }

    // A scheduler's stop waits up to 5 s for its poll and its heartbeats to end after their
    // interrupt. A virtual thread that waits for the database ends at the interrupt, where a
    // platform thread in a socket read waits for the database to answer.
    @Override
    public Optional<ExecutorService> dueExecutor() {
      return Optional.of(
          Executors.newSingleThreadExecutor(
              SystemWork.newThreadFactoryRunningAsSystemActor("scheduler-poll-")));
    }

    @Override
    public Optional<ScheduledExecutorService> housekeeperExecutor() {
      return Optional.of(
          Executors.newScheduledThreadPool(
              HOUSEKEEPER_THREADS,
              SystemWork.newThreadFactoryRunningAsSystemActor("scheduler-housekeeper-")));
    }

    // The library's single-statement PostgreSQL claim nests its LIMIT inside the UPDATE's IN
    // subquery. When the planner rescans that subquery per candidate row, rows the statement has
    // already updated are skipped and the LIMIT bounds each rescan, so one claim can pick every
    // due execution. The generic claim that ProbeCapacityJdbcCustomization extends selects the
    // limited rows first and updates exactly them.
    @Override
    public Optional<JdbcCustomization> jdbcCustomization() {
      return Optional.of(jdbcCustomization);
    }

    @Override
    public Optional<Serializer> serializer() {
      return Optional.of(serializer);
    }
  }
}
