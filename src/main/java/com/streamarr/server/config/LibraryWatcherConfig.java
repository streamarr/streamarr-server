package com.streamarr.server.config;

import com.streamarr.server.services.concurrency.Sleeper;
import com.streamarr.server.services.library.FileStabilityChecker;
import com.streamarr.server.services.library.PollingFileStabilityChecker;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LibraryWatcherConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public Sleeper sleeper() {
    return duration -> Thread.sleep(duration.toMillis());
  }

  @Bean
  public FileStabilityChecker fileStabilityChecker(
      Clock clock, LibraryWatcherProperties properties, Sleeper sleeper) {
    return new PollingFileStabilityChecker(clock, properties, sleeper);
  }
}
