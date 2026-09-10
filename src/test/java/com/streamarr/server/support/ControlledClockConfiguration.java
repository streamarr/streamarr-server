package com.streamarr.server.support;

import com.streamarr.server.fakes.MutableClock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ControlledClockConfiguration {

  @Bean
  @Primary
  MutableClock controlledClock() {
    return new MutableClock(new AtomicReference<>(Instant.now()));
  }
}
