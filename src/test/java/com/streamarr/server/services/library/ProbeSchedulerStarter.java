package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

// Starts a probe scheduler against the test database from the application's scheduler customizer,
// as the db-scheduler starter would. The test profile turns the starter off.
@Builder
record ProbeSchedulerStarter(
    DbSchedulerCustomizer customizer,
    ProbeTaskCompletion completion,
    DataSource dataSource,
    Environment environment) {

  static final int THREADS = 2;

  record StartedProbeScheduler(Scheduler scheduler, SchedulerClient client) {}

  static ProbeSchedulerStarter fromContext(ApplicationContext context) {
    return ProbeSchedulerStarter.builder()
        .customizer(context.getBean(DbSchedulerCustomizer.class))
        .completion(context.getBean(ProbeTaskCompletion.class))
        .dataSource(context.getBean(DataSource.class))
        .environment(context.getEnvironment())
        .build();
  }

  StartedProbeScheduler start(
      ProbeExecution execution, AbstractSchedulerListener listener, Clock clock) {
    var task = MediaProbeTask.create(execution, completion);
    var properties = UnstartedSchedulerConfiguration.schedulerProperties(environment);
    properties.setThreads(THREADS);
    var scheduler =
        DbSchedulerConfigurationSupport.buildScheduler(
            properties,
            customizer,
            StatsRegistry.NOOP,
            clock::instant,
            dataSource,
            List.of(task),
            List.of(listener),
            List.of());
    var client =
        SchedulerClient.Builder.create(dataSource, task)
            .serializer(customizer.serializer().orElseThrow())
            .build();
    scheduler.start();
    return new StartedProbeScheduler(scheduler, client);
  }
}
