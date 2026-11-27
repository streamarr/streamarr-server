package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Contained Failure Metrics Tests")
class ContainedFailureMetricsTest {

  @Test
  @DisplayName(
      "Should reject a missing meter registry when the contained failure metrics are created")
  void shouldRejectMissingMeterRegistryWhenTheContainedFailureMetricsAreCreated() {
    assertThatThrownBy(() -> new ContainedFailureMetrics(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("meterRegistry");
  }

  @Test
  @DisplayName("Should report every operation at zero when no failure has been counted")
  void shouldReportEveryOperationAtZeroWhenNoFailureHasBeenCounted() {
    var meterRegistry = new SimpleMeterRegistry();

    new ContainedFailureMetrics(meterRegistry);

    assertThat(meterRegistry.find("streamarr.streaming.contained_failures").counters())
        .extracting(counter -> counter.getId().getTag("operation"), Counter::count)
        .containsExactlyInAnyOrder(
            tuple("learned_stream_end_store", 0.0),
            tuple("segment_upload_discard", 0.0),
            tuple("retention_read", 0.0),
            tuple("disk_space_read", 0.0));
  }
}
