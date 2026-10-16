package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Probe Capacity Tests")
class ProbeCapacityTest {

  @ParameterizedTest(
      name = "advertised={0}, running={1}, packet scans={2}, executions in progress={3}")
  @CsvSource({
    "4, 0, 0, 0, 4",
    "4, 0, 0, 3, 1",
    "4, 3, 0, 1, 1",
    "4, 1, 1, 2, 1",
    "4, 2, 2, 3, 0",
    "4, 6, 0, 0, 0",
    "0, 0, 0, 0, 0"
  })
  @DisplayName(
      "Should leave the advertised capacity that neither the running probes nor the probe"
          + " executions in progress use when computing the free capacity")
  void
      shouldLeaveTheAdvertisedCapacityThatNeitherTheRunningProbesNorTheProbeExecutionsInProgressUse(
          int advertised, int running, int runningPacketScans, int executionsInProgress, int free) {
    var capacity =
        ProbeCapacity.builder()
            .advertised(advertised)
            .running(running)
            .runningPacketScans(runningPacketScans)
            .build();

    assertThat(capacity.free(executionsInProgress)).isEqualTo(free);
  }
}
