package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.streamarr.server.config.ProbeCapacityJdbcCustomization;
import com.streamarr.server.config.ProbeSchedulingConfiguration;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

// Starts a probe scheduler against the test database as the db-scheduler starter would, from the
// application's scheduler customization. The test profile turns the starter off. The scheduler
// picks
// probes up to the probe capacity it reads and its own thread count. A test whose fake producer
// stands in for the workers reads the capacity of FAKE_WORKERS; a test with a real worker session
// reads the context's ProbeCapacitySource.
@Builder(toBuilder = true)
record ProbeSchedulerStarter(
    DbSchedulerCustomizer customizer,
    ProbeCapacitySource probeCapacity,
    ProbeTaskCompletion completion,
    DataSource dataSource,
    Environment environment,
    int threads) {

  static final ProbeCapacitySource FAKE_WORKERS =
      () -> ProbeCapacity.builder().advertised(1_000).build();

  record StartedProbeScheduler(Scheduler scheduler, SchedulerClient client) {}

  static ProbeSchedulerStarter fromContext(ApplicationContext context) {
    return ProbeSchedulerStarter.builder()
        .customizer(context.getBean(DbSchedulerCustomizer.class))
        .probeCapacity(FAKE_WORKERS)
        .completion(context.getBean(ProbeTaskCompletion.class))
        .dataSource(context.getBean(DataSource.class))
        .environment(context.getEnvironment())
        .threads(2)
        .build();
  }

  StartedProbeScheduler start(
      ProbeExecution execution, AbstractSchedulerListener listener, Clock clock) {
    var task = MediaProbeTask.create(execution, completion);
    var properties = UnstartedSchedulerConfiguration.schedulerProperties(environment);
    properties.setThreads(threads);
    var serializer = customizer.serializer().orElseThrow();
    var pickingCustomizer =
        new ProbeSchedulingConfiguration()
            .dbSchedulerCustomizer(
                serializer, dataSource, new ProbeCapacityJdbcCustomization(probeCapacity, threads));
    var scheduler =
        DbSchedulerConfigurationSupport.buildScheduler(
            properties,
            pickingCustomizer,
            StatsRegistry.NOOP,
            clock::instant,
            dataSource,
            List.of(task),
            List.of(listener),
            List.of());
    var client = SchedulerClient.Builder.create(dataSource, task).serializer(serializer).build();
    scheduler.start();
    return new StartedProbeScheduler(scheduler, client);
  }
}
