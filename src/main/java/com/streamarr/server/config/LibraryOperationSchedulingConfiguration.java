package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.streamarr.server.services.library.LibraryOperationExecution;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class LibraryOperationSchedulingConfiguration {

  /**
   * Runs library operations on their own scheduler, which starts with the application only while
   * db-scheduler is enabled. A shutdown drains the scheduler's operations for the configured
   * timeout before it interrupts them.
   */
  @Bean
  public LibraryOperationScheduler libraryOperationScheduler(
      DbSchedulerCustomizer customizer,
      DataSource dataSource,
      LibraryOperationExecution execution,
      LibraryOperationSchedulingProperties properties,
      Clock clock,
      Environment environment) {
    // The db-scheduler settings the starter reads, bound even when the starter is off.
    var schedulerProperties =
        Binder.get(environment)
            .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
            .orElseGet(DbSchedulerProperties::new);
    schedulerProperties.setShutdownMaxWait(properties.shutdownDrainTimeout());
    return LibraryOperationScheduler.builder()
        .schedulerProperties(schedulerProperties)
        .customizer(customizer)
        .dataSource(dataSource)
        .execution(execution)
        .threads(properties.threads())
        .clock(clock)
        .autoStartup(schedulerProperties.isEnabled())
        .build();
  }
}
