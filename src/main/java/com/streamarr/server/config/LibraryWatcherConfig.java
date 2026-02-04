package com.streamarr.server.config;

import com.streamarr.server.services.library.FileStabilityChecker;
import com.streamarr.server.services.library.PollingFileStabilityChecker;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LibraryWatcherConfig {

  @Bean
  public FileStabilityChecker fileStabilityChecker(LibraryWatcherProperties properties) {
    return new PollingFileStabilityChecker(
        Clock.systemUTC(), properties, duration -> Thread.sleep(duration.toMillis()));
  }
}
