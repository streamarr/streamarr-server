package com.streamarr.server.config;

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
  public FileStabilityChecker fileStabilityChecker(
      Clock clock, LibraryWatcherProperties properties) {
    return new PollingFileStabilityChecker(
        clock, properties, duration -> Thread.sleep(duration.toMillis()));
  }
}
