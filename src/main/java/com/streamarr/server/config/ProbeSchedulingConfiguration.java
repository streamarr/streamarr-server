package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.SystemClock;
import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.TaskResolver;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.services.library.MediaProbeTask;
import com.streamarr.server.services.library.ProbeExecution;
import com.streamarr.server.services.library.ProbeTaskCompletion;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProbeSchedulingConfiguration {

  @Bean
  public Serializer probeTaskSerializer() {
    return new JacksonTaskDataSerializer();
  }

  @Bean
  public DbSchedulerCustomizer dbSchedulerCustomizer(
      Serializer probeTaskSerializer, DataSource dataSource) {
    return new VirtualThreadCustomizer(probeTaskSerializer, dataSource);
  }

  @Bean
  public Task<ProbeTaskRequest> mediaProbeTask(
      ProbeExecution execution, ProbeTaskCompletion completion, Clock clock) {
    return MediaProbeTask.create(execution, completion, clock);
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

    @Override
    public Optional<DataSource> dataSource() {
      return Optional.of(new SchedulerTransactionDataSource(dataSource));
    }

    @Override
    public Optional<ExecutorService> executorService() {
      return Optional.of(Executors.newVirtualThreadPerTaskExecutor());
    }

    // The library's single-statement PostgreSQL claim nests its LIMIT inside the UPDATE's IN
    // subquery. When the planner rescans that subquery per candidate row, rows the statement has
    // already updated are skipped and the LIMIT bounds each rescan, so one claim can pick every
    // due execution. The generic claim selects the limited rows first and updates exactly them.
    @Override
    public Optional<JdbcCustomization> jdbcCustomization() {
      return Optional.of(new PostgreSqlJdbcCustomization(true, false));
    }

    @Override
    public Optional<Serializer> serializer() {
      return Optional.of(serializer);
    }
  }
}
