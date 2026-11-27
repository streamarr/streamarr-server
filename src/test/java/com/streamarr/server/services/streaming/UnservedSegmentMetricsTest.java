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
@DisplayName("Unserved Segment Metrics Tests")
class UnservedSegmentMetricsTest {

  @Test
  @DisplayName(
      "Should reject a missing meter registry when the unserved segment metrics are created")
  void shouldRejectMissingMeterRegistryWhenTheUnservedSegmentMetricsAreCreated() {
    assertThatThrownBy(() -> new UnservedSegmentMetrics(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("meterRegistry");
  }

  @Test
  @DisplayName("Should report every cause at zero when no unserved segment has been counted")
  void shouldReportEveryCauseAtZeroWhenNoUnservedSegmentHasBeenCounted() {
    var meterRegistry = new SimpleMeterRegistry();

    new UnservedSegmentMetrics(meterRegistry);

    assertThat(meterRegistry.find("streamarr.streaming.unserved_segments").counters())
        .extracting(counter -> counter.getId().getTag("cause"), Counter::count)
        .containsExactlyInAnyOrder(
            tuple("session_ended", 0.0),
            tuple("disk_pressure_eviction", 0.0),
            tuple("session_start_failed", 0.0),
            tuple("server_shutdown", 0.0));
  }
}
