package com.streamarr.server.services.library;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Reports the probes that running scans still wait for. A probe waits unclaimed while no worker has
 * a free unit for it, so the count shows the work that the workers' capacities hold back.
 */
@Component
public class ScanProbesWaiting {

  private static final String WAITING_METRIC = "streamarr.probes.waiting";

  private final Map<ProbeRun, Integer> pendingByRun = new ConcurrentHashMap<>();

  public ScanProbesWaiting(@NonNull MeterRegistry meterRegistry) {
    Gauge.builder(WAITING_METRIC, this, ScanProbesWaiting::total)
        .description("The probes that running scans still wait for")
        .register(meterRegistry);
  }

  void pending(@NonNull ProbeRun run, int pending) {
    pendingByRun.put(run, pending);
  }

  void ended(@NonNull ProbeRun run) {
    pendingByRun.remove(run);
  }

  private int total() {
    return pendingByRun.values().stream().mapToInt(Integer::intValue).sum();
  }
}
