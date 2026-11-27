package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The meters of a test of {@link UnmeasuredCopyListener}. They count as a {@link
 * SimpleMeterRegistry} does and signal every counted outcome. The count is a raise's only outcome
 * and lands on the raise's own thread, so a test waits for the signal.
 */
final class RaiseOutcomeMeters extends SimpleMeterRegistry {

  private static final String MOVED_SCANS_METRIC = "streamarr.probes.bitrate_scans_moved_forward";
  private static final Duration BOUND = Duration.ofSeconds(10);

  private final Map<String, Semaphore> counted = new ConcurrentHashMap<>();

  @Override
  protected Counter newCounter(Meter.Id id) {
    var counter = super.newCounter(id);
    var signal = signalOf(id.getTag("outcome"));
    return new Counter() {
      @Override
      public void increment(double amount) {
        counter.increment(amount);
        signal.release();
      }

      @Override
      public double count() {
        return counter.count();
      }

      @Override
      public Id getId() {
        return id;
      }
    };
  }

  /** Waits until a raise counts the outcome. Each count answers one wait. */
  void awaitCounted(String outcome) {
    try {
      assertThat(signalOf(outcome).tryAcquire(BOUND.toSeconds(), TimeUnit.SECONDS))
          .as("A raise counted the outcome %s", outcome)
          .isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a raise", interrupted);
    }
  }

  double countOf(String outcome) {
    return counter(MOVED_SCANS_METRIC, "outcome", outcome).count();
  }

  private Semaphore signalOf(String outcome) {
    return counted.computeIfAbsent(String.valueOf(outcome), _ -> new Semaphore(0));
  }
}
