package com.streamarr.server.support;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.services.library.LibraryOperationExecution;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Starts a library-operation scheduler on the shared table, built as the application builds its
 * own, with a listener that a test waits on. A shutdown interrupts its executions after 200 ms. A
 * test that starts one stops the context's scheduler first, so that it does not pick the test's
 * executions.
 */
@Builder(toBuilder = true)
public record LibraryOperationSchedulerStarter(
    DbSchedulerCustomizer customizer,
    DataSource dataSource,
    LibraryOperationExecution execution,
    Environment environment,
    Clock clock) {

  public static LibraryOperationSchedulerStarter fromContext(ApplicationContext context) {
    return LibraryOperationSchedulerStarter.builder()
        .customizer(context.getBean(DbSchedulerCustomizer.class))
        .dataSource(context.getBean(DataSource.class))
        .execution(context.getBean(LibraryOperationExecution.class))
        .environment(context.getEnvironment())
        .clock(Clock.systemUTC())
        .build();
  }

  /** Starts a scheduler that runs library operations; the caller stops it. */
  public LibraryOperationScheduler start(SchedulerListener listener) {
    var properties = UnstartedSchedulerConfiguration.schedulerProperties(environment);
    properties.setShutdownMaxWait(Duration.ofMillis(200));
    properties.setPollingInterval(Duration.ofMillis(100));
    var scheduler =
        LibraryOperationScheduler.builder()
            .schedulerProperties(properties)
            .customizer(customizer)
            .dataSource(dataSource)
            .execution(execution)
            .threads(2)
            .clock(clock)
            .listeners(List.of(listener))
            .build();
    scheduler.start();
    return scheduler;
  }
}
