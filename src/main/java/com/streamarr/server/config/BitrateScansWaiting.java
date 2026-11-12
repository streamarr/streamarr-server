package com.streamarr.server.config;

import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.concurrency.NanoClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Reports the bitrate scans that wait in the scan table (ADR 0054) as the last count read them. A
 * count runs on its own thread once every refresh interval, so a metrics publish reads only memory
 * and never waits for the database. The gauge reports no value, {@code NaN}, before the first count
 * ends, after a count fails, and once the last count is older than two refresh intervals, as while
 * a count waits for a database that stopped answering.
 */
@Slf4j
public final class BitrateScansWaiting implements MeterBinder, SmartLifecycle {

  private static final String WAITING_METRIC = "streamarr.probes.bitrate_scans_waiting";
  private static final LastCount NO_COUNT = new LastCount(Double.NaN, 0);

  private final LongSupplier count;
  private final Duration refreshInterval;
  private final NanoClock nanoClock;
  // The next count begins one refresh interval after the last one ends, so by this age the next
  // count has run about one refresh interval without an end.
  private final long staleAfterNanos;
  private final AtomicReference<LastCount> lastCount = new AtomicReference<>(NO_COUNT);
  // Guarded by this monitor.
  private ScheduledExecutorService refreshes;

  @Builder
  private BitrateScansWaiting(
      @NonNull LongSupplier count,
      @NonNull Duration refreshInterval,
      @NonNull NanoClock nanoClock) {
    this.count = count;
    this.refreshInterval = refreshInterval;
    this.nanoClock = nanoClock;
    this.staleAfterNanos = refreshInterval.multipliedBy(2).toNanos();
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(WAITING_METRIC, this::reported)
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

  private double reported() {
    var last = lastCount.get();
    if (nanoClock.nanoTime() - last.beganNanos() > staleAfterNanos) {
      return Double.NaN;
    }

    return last.waiting();
  }

  private void refresh() {
    var began = nanoClock.nanoTime();
    try {
      lastCount.set(new LastCount((double) count.getAsLong(), began));
    } catch (RuntimeException e) {
      lastCount.set(NO_COUNT);
      log.debug("Counting the waiting bitrate scans failed.", e);
    }
  }

  private record LastCount(double waiting, long beganNanos) {}
}
