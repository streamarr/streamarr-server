package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe Capacity Tests")
class ProbeCapacityTest {

  @Test
  @DisplayName("Should add each worker's capacity and free units when workers are combined")
  void shouldAddEachWorkersCapacityAndFreeUnitsWhenWorkersAreCombined() {
    var first =
        ProbeCapacity.builder()
            .backgroundProbeCapacity(3)
            .freePlaybackProbeUnits(2)
            .freeBackgroundProbeUnits(1)
            .freeKeyframePacketScanUnits(0)
            .playbackProbeCapacity(2)
            .keyframePacketScanCapacity(1)
            .runningProbes(2)
            .runningPlaybackProbes(1)
            .runningKeyframePacketScans(1)
            .build();
    var second =
        ProbeCapacity.builder()
            .backgroundProbeCapacity(5)
            .freePlaybackProbeUnits(1)
            .freeBackgroundProbeUnits(5)
            .freeKeyframePacketScanUnits(1)
            .playbackProbeCapacity(2)
            .keyframePacketScanCapacity(1)
            .runningProbes(1)
            .build();

    assertThat(first.plus(second))
        .isEqualTo(
            ProbeCapacity.builder()
                .backgroundProbeCapacity(8)
                .freePlaybackProbeUnits(3)
                .freeBackgroundProbeUnits(6)
                .freeKeyframePacketScanUnits(1)
                .playbackProbeCapacity(4)
                .keyframePacketScanCapacity(2)
                .runningProbes(3)
                .runningPlaybackProbes(1)
                .runningKeyframePacketScans(1)
                .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("probeClaims")
  @DisplayName(
      "Should claim the free units of each kind that no probe execution awaiting dispatch may take"
          + " when probes are due")
  void shouldClaimTheFreeUnitsOfEachKindThatNoExecutionAwaitingDispatchMayTakeWhenProbesAreDue(
      ProbeClaimCase claimCase) {
    assertThat(claimCase.capacity().probesToClaim(claimCase.inProgress()))
        .isEqualTo(claimCase.claim());
  }

  static Stream<ProbeClaimCase> probeClaims() {
    return Stream.of(
        new ProbeClaimCase(
            "every unit free and nothing in progress",
            free().build(),
            inProgress(0, 0),
            claim(4, 2)),
        new ProbeClaimCase(
            "every execution in progress runs on a worker",
            free().runningProbes(3).build(),
            inProgress(3, 0),
            claim(4, 2)),
        new ProbeClaimCase(
            "a background execution awaits dispatch",
            free().runningProbes(0).build(),
            inProgress(1, 0),
            claim(3, 2)),
        new ProbeClaimCase(
            "four background executions prepare or publish while no probe runs",
            free().build(),
            inProgress(4, 0),
            claim(0, 2)),
        new ProbeClaimCase(
            "a playback execution awaits dispatch", free().build(), inProgress(1, 1), claim(4, 1)),
        new ProbeClaimCase(
            "three playback executions await one free playback unit",
            free().freePlaybackProbeUnits(1).build(),
            inProgress(3, 3),
            claim(2, 0)),
        new ProbeClaimCase(
            "one playback probe runs and another awaits dispatch",
            free().freePlaybackProbeUnits(1).runningProbes(1).runningPlaybackProbes(1).build(),
            inProgress(2, 2),
            claim(4, 0)),
        new ProbeClaimCase(
            "no unit is free",
            ProbeCapacity.builder().runningProbes(4).build(),
            inProgress(4, 0),
            claim(0, 0)));
  }

  // Two free playback units and four free background units.
  private static ProbeCapacity.ProbeCapacityBuilder free() {
    return ProbeCapacity.builder().freePlaybackProbeUnits(2).freeBackgroundProbeUnits(4);
  }

  private static ProbeExecutionsInProgress inProgress(int executions, int playbackExecutions) {
    return ProbeExecutionsInProgress.builder()
        .executions(executions)
        .playbackExecutions(playbackExecutions)
        .build();
  }

  private static ProbeClaim claim(int backgroundProbes, int playbackProbes) {
    return ProbeClaim.builder()
        .backgroundProbes(backgroundProbes)
        .playbackProbes(playbackProbes)
        .build();
  }

  record ProbeClaimCase(
      String description,
      ProbeCapacity capacity,
      ProbeExecutionsInProgress inProgress,
      ProbeClaim claim) {

    @Override
    public String toString() {
      return description;
    }
  }

  @ParameterizedTest(name = "free={0}, running={1}, in progress={2} -> {3}")
  @CsvSource({"1, 0, 0, 1", "1, 1, 1, 1", "1, 0, 1, 0", "0, 1, 1, 0", "2, 0, 1, 1"})
  @DisplayName(
      "Should claim the free scan units that no claimed scan awaiting dispatch may take when scans"
          + " are due")
  void shouldClaimTheFreeScanUnitsThatNoClaimedScanAwaitingDispatchMayTakeWhenScansAreDue(
      int free, int running, int inProgress, int claim) {
    var capacity =
        ProbeCapacity.builder()
            .freeKeyframePacketScanUnits(free)
            .runningKeyframePacketScans(running)
            .build();

    assertThat(capacity.keyframePacketScansToClaim(inProgress)).isEqualTo(claim);
  }

  @Test
  @DisplayName("Should claim the background and playback probes together when counting a claim")
  void shouldClaimTheBackgroundAndPlaybackProbesTogetherWhenCountingAClaim() {
    assertThat(claim(3, 2).total()).isEqualTo(5);
  }
}
