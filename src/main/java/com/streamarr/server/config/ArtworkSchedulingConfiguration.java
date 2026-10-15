package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkStepsRunner;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class ArtworkSchedulingConfiguration {

  private static final String PRIMARY_TABLE = "primary_artwork_scheduled_tasks";
  private static final String BACKGROUND_TABLE = "background_artwork_scheduled_tasks";
  private static final String PRIMARY_TASK = "primary-artwork-steps";
  private static final String BACKGROUND_TASK = "background-artwork-steps";

  @Bean
  @SuppressWarnings("java:S107") // Spring injects each collaborator by type.
  public ArtworkSchedulers artworkSchedulers(
      DbSchedulerCustomizer customizer,
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      ArtworkStepsRunner stepsRunner,
      ArtworkRequestRepository requests,
      Clock clock,
      ArtworkProperties properties,
      Environment environment) {
    // Each scheduler gets its own builder and settings, because building one changes its settings.
    Supplier<ArtworkScheduler.ArtworkSchedulerBuilder> newScheduler =
        () ->
            ArtworkScheduler.builder()
                .schedulerProperties(schedulerProperties(environment))
                .customizer(customizer)
                .dataSource(dataSource)
                .transactionManager(transactionManager)
                .stepsRunner(stepsRunner)
                .requests(requests)
                .clock(clock)
                .listeners(List.of());
    return ArtworkSchedulers.builder()
        .primary(
            newScheduler
                .get()
                .tableName(PRIMARY_TABLE)
                .taskName(PRIMARY_TASK)
                .threads(properties.primaryThreads())
                .build())
        .background(
            newScheduler
                .get()
                .tableName(BACKGROUND_TABLE)
                .taskName(BACKGROUND_TASK)
                .threads(properties.backgroundThreads())
                .build())
        .autoStartup(environment.getProperty("db-scheduler.enabled", Boolean.class, true))
        .build();
  }

  // The db-scheduler settings the starter reads, bound even when the starter is off.
  private static DbSchedulerProperties schedulerProperties(Environment environment) {
    return Binder.get(environment)
        .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
        .orElseGet(DbSchedulerProperties::new);
  }
}
