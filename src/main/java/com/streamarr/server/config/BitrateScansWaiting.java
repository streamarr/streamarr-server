package com.streamarr.server.config;

import com.streamarr.server.services.audit.SystemWork;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Reports the bitrate scans that wait in the scan table (ADR 0054) as the last count read them. A
 * count runs on its own thread once every refresh interval, so a metrics publish reads only memory
 * and never waits for the database. The gauge reports no value, {@code NaN}, before the first count
 * ends and after a count fails.
 */
@Slf4j
public final class BitrateScansWaiting implements MeterBinder, SmartLifecycle {

  private static final String WAITING_METRIC = "streamarr.probes.bitrate_scans_waiting";

  private final LongSupplier count;
  private final Duration refreshInterval;
  private volatile double lastCount = Double.NaN;
  // Guarded by this monitor.
  private ScheduledExecutorService refreshes;

  @Builder
  private BitrateScansWaiting(@NonNull LongSupplier count, @NonNull Duration refreshInterval) {
    this.count = count;
    this.refreshInterval = refreshInterval;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(WAITING_METRIC, () -> lastCount)
        .description("Bitrate scans that wait in the scan table for their next chunk, last counted")
        .register(registry);
  }

  @Override
  public synchronized void start() {
    refreshes =
        Executors.newSingleThreadScheduledExecutor(
            SystemWork.newThreadFactoryRunningAsSystemActor("bitrate-scans-waiting-"));
    refreshes.scheduleWithFixedDelay(
        this::refresh, 0, refreshInterval.toNanos(), TimeUnit.NANOSECONDS);
  }

  // Interrupts a count that waits for the database and returns without waiting for it, so a count
  // never holds the server's stop.
  @Override
  public synchronized void stop() {
    refreshes.shutdownNow();
    refreshes = null;
  }

  @Override
  public synchronized boolean isRunning() {
    return refreshes != null;
  }

  @Override
  public int getPhase() {
    return BackgroundWorkStop.PHASE;
  }

  private void refresh() {
    try {
      lastCount = (double) count.getAsLong();
    } catch (RuntimeException e) {
      lastCount = Double.NaN;
      log.debug("Counting the waiting bitrate scans failed.", e);
    }
  }
}
