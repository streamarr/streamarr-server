package com.streamarr.server.support;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.streamarr.server.config.ArtworkScheduler;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.ArtworkStepsRunner;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Starts an artwork scheduler on the table of one artwork priority, built as the application builds
 * its own, with a listener that a test waits on. A test that starts one stops the context's artwork
 * schedulers first, so that they do not pick its executions.
 */
@Builder(toBuilder = true)
public record ArtworkSchedulerStarter(
    DbSchedulerCustomizer customizer,
    DataSource dataSource,
    PlatformTransactionManager transactionManager,
    ArtworkStepsRunner stepsRunner,
    ArtworkRequestRepository requests,
    Environment environment,
    Clock clock) {

  public static final String PRIMARY_TABLE = "primary_artwork_scheduled_tasks";
  public static final String BACKGROUND_TABLE = "background_artwork_scheduled_tasks";

  public static ArtworkSchedulerStarter fromContext(ApplicationContext context) {
    return ArtworkSchedulerStarter.builder()
        .customizer(context.getBean(DbSchedulerCustomizer.class))
        .dataSource(context.getBean(DataSource.class))
        .transactionManager(context.getBean(PlatformTransactionManager.class))
        .stepsRunner(context.getBean(ArtworkStepsRunner.class))
        .requests(context.getBean(ArtworkRequestRepository.class))
        .environment(context.getEnvironment())
        .clock(Clock.systemUTC())
        .build();
  }

  /** Starts a scheduler that runs the artwork steps of {@code priority}; the caller stops it. */
  public ArtworkScheduler start(ArtworkPriority priority, SchedulerListener listener) {
    var properties = UnstartedSchedulerConfiguration.schedulerProperties(environment);
    properties.setShutdownMaxWait(Duration.ofMillis(200));
    properties.setPollingInterval(Duration.ofMillis(100));
    var scheduler =
        ArtworkScheduler.builder()
            .schedulerProperties(properties)
            .customizer(customizer)
            .dataSource(dataSource)
            .transactionManager(transactionManager)
            .stepsRunner(stepsRunner)
            .requests(requests)
            .tableName(tableOf(priority))
            .taskName(priority.name().toLowerCase() + "-artwork-steps")
            .threads(2)
            .clock(clock)
            .listeners(List.of(listener))
            .build();
    scheduler.start();
    return scheduler;
  }

  private static String tableOf(ArtworkPriority priority) {
    return switch (priority) {
      case PRIMARY -> PRIMARY_TABLE;
      case BACKGROUND -> BACKGROUND_TABLE;
    };
  }
}
