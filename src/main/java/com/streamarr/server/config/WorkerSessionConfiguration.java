package com.streamarr.server.config;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerSessionProperties.class)
public class WorkerSessionConfiguration {

  @Bean(initMethod = "start", destroyMethod = "close")
  public WorkerSessionServer workerSessionServer(
      WorkerSessionProperties properties, SegmentStore segmentStore, MeterRegistry meterRegistry) {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .address(properties.address())
            .port(properties.port())
            .probeTimeout(properties.probeTimeout())
            .probeCancellationTimeout(properties.probeCancellationTimeout())
            .build();
    return new WorkerSessionServer(configuration, segmentStore, meterRegistry);
  }
}
