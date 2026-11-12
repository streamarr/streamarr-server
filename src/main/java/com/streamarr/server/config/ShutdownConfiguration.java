package com.streamarr.server.config;

import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fits the server's shutdown within one deadline (see {@link BackgroundWorkStop}). */
@Configuration(proxyBeanMethods = false)
public class ShutdownConfiguration {

  /** Stops the scheduled tasks together with the background schedulers and the web server. */
  @Bean
  public SimpleAsyncTaskSchedulerCustomizer scheduledTasksStopWithTheBackgroundWork() {
    return scheduler -> scheduler.setPhase(BackgroundWorkStop.PHASE);
  }

  @Bean
  public static BoundedConnectionPoolClose boundedConnectionPoolClose() {
    return new BoundedConnectionPoolClose();
  }
}
