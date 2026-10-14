package com.streamarr.server.support;

import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

/** Reads the gauge of registered subscription operations, one series per subscription field. */
@RequiredArgsConstructor
public final class ActiveSubscriptions {

  // Operators chart this name, so the tests spell it out: renaming the metric fails them.
  private static final String METRIC = "streamarr.graphql.subscriptions.active";
  private static final Duration BOUND = Duration.ofSeconds(10);

  private final MeterRegistry meterRegistry;

  public double count(String field) {
    var gauge = meterRegistry.find(METRIC).tag("field", field).gauge();
    if (gauge == null) {
      return 0;
    }

    return gauge.value();
  }

  // Registration and release send no protocol message (ADR 0042), so tests wait on the gauge.
  public void awaitCount(String field, int expected) {
    await().atMost(BOUND).until(() -> count(field) == expected);
  }

  /** Waits until no field has a registered operation, so the next test starts from zero. */
  public void awaitNoneActive() {
    await()
        .atMost(BOUND)
        .until(
            () ->
                meterRegistry.find(METRIC).gauges().stream().allMatch(ActiveSubscriptions::isZero));
  }

  private static boolean isZero(Gauge gauge) {
    return gauge.value() == 0;
  }
}
