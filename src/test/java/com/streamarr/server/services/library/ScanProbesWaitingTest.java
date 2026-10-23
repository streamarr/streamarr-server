package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Scan Probes Waiting Tests")
class ScanProbesWaitingTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ScanProbesWaiting waiting = new ScanProbesWaiting(meterRegistry);

  @Test
  @DisplayName("Should report the probes of every waiting scan when two scans wait at once")
  void shouldReportTheProbesOfEveryWaitingScanWhenTwoScansWaitAtOnce() {
    var first = new ProbeRun(null, Clock.systemUTC());
    var second = new ProbeRun(null, Clock.systemUTC());

    waiting.pending(first, 3);
    waiting.pending(second, 2);
    waiting.pending(first, 1);

    assertThat(gauge()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should leave out a scan's probes when the scan stops waiting")
  void shouldLeaveOutAScansProbesWhenTheScanStopsWaiting() {
    var first = new ProbeRun(null, Clock.systemUTC());
    var second = new ProbeRun(null, Clock.systemUTC());
    waiting.pending(first, 3);
    waiting.pending(second, 2);

    waiting.ended(first);

    assertThat(gauge()).isEqualTo(2);
  }

  private double gauge() {
    return meterRegistry.get("streamarr.probes.waiting").gauge().value();
  }
}
