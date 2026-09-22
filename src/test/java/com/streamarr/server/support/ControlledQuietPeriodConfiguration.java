package com.streamarr.server.support;

import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.fakes.VirtualTimeSleeper;
import com.streamarr.server.services.library.FileStabilityChecker;
import com.streamarr.server.services.library.PollingFileStabilityChecker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ControlledQuietPeriodConfiguration {

  @Bean
  VirtualTimeSleeper quietPeriodSleeper() {
    return new VirtualTimeSleeper();
  }

  @Bean
  @Primary
  FileStabilityChecker controlledFileStabilityChecker(
      VirtualTimeSleeper quietPeriodSleeper, LibraryWatcherProperties properties) {
    return new PollingFileStabilityChecker(
        quietPeriodSleeper.clock(), properties, quietPeriodSleeper);
  }
}
