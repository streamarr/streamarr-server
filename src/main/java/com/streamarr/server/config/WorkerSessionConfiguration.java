package com.streamarr.server.config;

import com.streamarr.server.properties.WorkerSessionProperties;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.ProbeReportLimits;
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
            .bitrateScanChunkTimeout(properties.bitrateScanChunkTimeout())
            .probeCancellationTimeout(properties.probeCancellationTimeout())
            .startReportTimeout(properties.startReportTimeout())
            .keepalive(
                new WorkerSessionKeepalive(
                    properties.keepalive().time(), properties.keepalive().timeout()))
            .probeReportLimits(probeReportLimits(properties.probeReport()))
            .build();
    return WorkerSessionServer.builder()
        .configuration(configuration)
        .segmentStore(segmentStore)
        .meterRegistry(meterRegistry)
        .eventPublisher(eventPublisher)
        .build();
  }

  private static ProbeReportLimits probeReportLimits(WorkerSessionProperties.ProbeReport report) {
    return ProbeReportLimits.builder()
        .keyframeLimit(report.keyframeLimit())
        .measuredKeyframeIntervalLimit(report.measuredKeyframeIntervalLimit())
        .lengthLimitBytes(report.lengthLimit().toBytes())
        .resultLengthLimitBytes(report.resultLengthLimit().toBytes())
        .idleTimeout(report.idleTimeout())
        .memoryBudgetBytes(report.memoryBudget().toBytes())
        .build();
  }

  @Bean
  public HeldSegmentRequests heldSegmentRequests(WorkerSessionServer workerSessionServer) {
    return workerSessionServer.heldSegmentRequests();
  }
}
