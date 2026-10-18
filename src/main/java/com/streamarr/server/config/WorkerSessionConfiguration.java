package com.streamarr.server.config;

import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionKeepalive;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerSessionProperties.class)
public class WorkerSessionConfiguration {

  private final ApplicationEventPublisher eventPublisher;

  @Bean(initMethod = "start", destroyMethod = "close")
  public WorkerSessionServer workerSessionServer(
      WorkerSessionProperties properties, SegmentStore segmentStore, MeterRegistry meterRegistry) {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .address(properties.address())
            .port(properties.port())
            .probeTimeout(properties.probeTimeout())
            .keyframeScanTimeout(properties.keyframeScanTimeout())
            .probeCancellationTimeout(properties.probeCancellationTimeout())
            .startReportTimeout(properties.startReportTimeout())
            .keepalive(
                new WorkerSessionKeepalive(
                    properties.keepalive().time(), properties.keepalive().timeout()))
            .build();
    return WorkerSessionServer.builder()
        .configuration(configuration)
        .segmentStore(segmentStore)
        .meterRegistry(meterRegistry)
        .eventPublisher(eventPublisher)
        .build();
  }

  @Bean
  public HeldSegmentRequests heldSegmentRequests(WorkerSessionServer workerSessionServer) {
    return workerSessionServer.heldSegmentRequests();
  }
}
