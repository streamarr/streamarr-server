package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.awaitility.core.ConditionFactory;

/** Reads the gauge of registered subscription operations, one series per subscription field. */
@RequiredArgsConstructor
public final class ActiveSubscriptions {

  // Operators chart this name, so the tests spell it out: renaming the metric fails them.
  private static final String METRIC = "streamarr.graphql.subscriptions.active";
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

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
    awaitGauge()
        .untilAsserted(
            () ->
                assertThat(count(field)).as("registered %s operations", field).isEqualTo(expected));
  }

  /** Waits until no field has a registered operation, so the next test starts from zero. */
  public void awaitNoneActive() {
    awaitGauge()
        .untilAsserted(
            () ->
                assertThat(meterRegistry.find(METRIC).gauges())
                    .allSatisfy(
                        gauge ->
                            assertThat(gauge.value())
                                .as("registered %s operations", gauge.getId().getTag("field"))
                                .isZero()));
  }

  // The gauge is an in-memory read, so checking at once and often costs nothing.
  private static ConditionFactory awaitGauge() {
    return await().atMost(BOUND).pollDelay(Duration.ZERO).pollInterval(POLL_INTERVAL);
  }
}
