package com.streamarr.server.config;

import com.streamarr.server.services.concurrency.CpuPermits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CpuPermitsConfiguration {

  /**
   * One permit per available processor unless {@code cpu.permits} sets another number. Image
   * decoding, resizing, and BlurHash encoding each hold a permit while they run.
   */
  @Bean
  public CpuPermits cpuPermits(@Value("${cpu.permits:0}") int configuredPermits) {
    var permits = configuredPermits;
    if (permits <= 0) {
      permits = Runtime.getRuntime().availableProcessors();
    }

    return new CpuPermits(permits);
  }
}
