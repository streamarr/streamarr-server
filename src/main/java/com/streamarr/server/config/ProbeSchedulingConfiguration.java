package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.PollingStrategyConfig;
import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.SystemClock;
import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.TaskResolver;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.library.KeyframePacketScanExecution;
import com.streamarr.server.services.library.MediaProbeTask;
import com.streamarr.server.services.library.ProbeExecution;
import com.streamarr.server.services.library.ProbeTaskCompletion;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
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
      MediaFileContainerInfoRepository outcomes,
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
        .outcomes(outcomes)
        .properties(properties)
        .clock(clock)
        .autoStartup(schedulerProperties.isEnabled())
        .build();
  }

  /**
   * Completes probe executions through the scheduler's own table and transaction. Unlike {@code
   * ExecutionOperations}, its selective reschedule can leave the failure history untouched.
   */
  @Bean
  public TaskRepository probeTaskRepository(DataSource dataSource, Serializer probeTaskSerializer) {
    var clock = new SystemClock();
    return new JdbcTaskRepository(
        new SchedulerTransactionDataSource(dataSource),
        false,
        new AutodetectJdbcCustomization(dataSource),
        JdbcTaskRepository.DEFAULT_TABLE_NAME,
        new TaskResolver(SchedulerListeners.NOOP, clock, List.of()),
        new SchedulerName.Fixed("probe-task-completion"),
        probeTaskSerializer,
        false,
        clock);
  }

  @RequiredArgsConstructor
  private static final class VirtualThreadCustomizer implements DbSchedulerCustomizer {

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
