package com.streamarr.server.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "streaming.worker-session")
public record WorkerSessionProperties(
    @DefaultValue("127.0.0.1") String address,
    @DefaultValue("9090") int port,
    @DefaultValue("1m") Duration probeTimeout,
    @DefaultValue("30m") Duration keyframeScanTimeout,
    @DefaultValue("5m") Duration bitrateScanChunkTimeout,
    @DefaultValue("5s") Duration probeCancellationTimeout,
    @DefaultValue("10s") Duration startReportTimeout,
    @DefaultValue Keepalive keepalive,
    @DefaultValue ProbeReport probeReport) {

  public record Keepalive(
      @DefaultValue("30s") Duration time, @DefaultValue("10s") Duration timeout) {}

  /** The bounds on the probe reports that workers send in report calls. */
  public record ProbeReport(
      @DefaultValue("150000") int keyframeLimit,
      @DefaultValue("1000000") int measuredKeyframeIntervalLimit,
      @DefaultValue("16MB") DataSize lengthLimit,
      @DefaultValue("30s") Duration idleTimeout,
      @DefaultValue("64MB") DataSize memoryBudget) {}
}
