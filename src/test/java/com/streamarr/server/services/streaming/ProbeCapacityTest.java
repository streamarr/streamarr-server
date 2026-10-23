package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            .runningProbes(2)
            .runningKeyframePacketScans(1)
            .build();
    var second =
        ProbeCapacity.builder()
            .backgroundProbeCapacity(5)
            .freePlaybackProbeUnits(1)
            .freeBackgroundProbeUnits(5)
            .freeKeyframePacketScanUnits(1)
            .runningProbes(1)
            .build();

    assertThat(first.plus(second))
        .isEqualTo(
            ProbeCapacity.builder()
                .backgroundProbeCapacity(8)
                .freePlaybackProbeUnits(3)
                .freeBackgroundProbeUnits(6)
                .freeKeyframePacketScanUnits(1)
                .runningProbes(3)
                .runningKeyframePacketScans(1)
                .build());
  }

  @ParameterizedTest(
      name =
          "free playback={0}, free background={1}, running={2}, in progress={3} -> {4} background,"
              + " {5} playback")
  @CsvSource({
    "2, 4, 0, 0, 4, 2",
    "2, 4, 3, 3, 4, 2",
    "2, 4, 0, 1, 3, 1",
    "2, 1, 0, 3, 0, 0",
    "0, 0, 4, 4, 0, 0",
    "2, 0, 4, 4, 0, 2"
  })
  @DisplayName(
      "Should claim the free units that no claimed probe awaiting dispatch may take when probes"
          + " are due")
  void shouldClaimTheFreeUnitsThatNoClaimedProbeAwaitingDispatchMayTakeWhenProbesAreDue(
      int freePlayback,
      int freeBackground,
      int running,
      int inProgress,
      int backgroundClaim,
      int playbackClaim) {
    var capacity =
        ProbeCapacity.builder()
            .freePlaybackProbeUnits(freePlayback)
            .freeBackgroundProbeUnits(freeBackground)
            .runningProbes(running)
            .build();

    assertThat(capacity.probesToClaim(inProgress))
        .isEqualTo(new ProbeClaim(backgroundClaim, playbackClaim));
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
    assertThat(new ProbeClaim(3, 2).total()).isEqualTo(5);
  }
}
