package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.services.library.MediaProbeTask;
import com.streamarr.server.services.library.ProbeExecution;
import com.streamarr.server.services.library.ProbeTaskCompletion;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

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
  public Task<ProbeRequest> mediaProbeTask(
      ProbeExecution execution, ProbeTaskCompletion completion, Clock clock) {
    return MediaProbeTask.create(execution, completion, clock);
  }

  /**
   * Requests are recorded through a client that exists even when the scheduler runtime is disabled,
   * so the transactional request path never depends on the poller.
   */
  @Bean
  public SchedulerClient probeSchedulerClient(
      DataSource dataSource, Task<ProbeRequest> mediaProbeTask, Serializer probeTaskSerializer) {
    return SchedulerClient.Builder.create(
            new TransactionAwareDataSourceProxy(dataSource), mediaProbeTask)
        .serializer(probeTaskSerializer)
        .build();
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

    @Override
    public Optional<Serializer> serializer() {
      return Optional.of(serializer);
    }
  }
}
