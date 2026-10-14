package com.streamarr.server.support;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SystemClock;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.Task;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Supplies the scheduler that the db-scheduler starter supplies in production. The test profile
 * turns the starter off so that no test polls by accident; this scheduler records requests the same
 * way but never starts, and a test that needs executions starts its own scheduler.
 */
@TestConfiguration(proxyBeanMethods = false)
public class UnstartedSchedulerConfiguration {

  @Bean
  Scheduler unstartedScheduler(
      DbSchedulerCustomizer customizer,
      DataSource dataSource,
      List<Task<?>> tasks,
      Environment environment) {
    return DbSchedulerConfigurationSupport.buildScheduler(
        schedulerProperties(environment),
        customizer,
        StatsRegistry.NOOP,
        new SystemClock(),
        dataSource,
        tasks,
        List.of(),
        List.of());
  }

  /** The db-scheduler settings the starter would read, bound even though the starter is off. */
  public static DbSchedulerProperties schedulerProperties(Environment environment) {
    return Binder.get(environment)
        .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
        .get();
  }
}
