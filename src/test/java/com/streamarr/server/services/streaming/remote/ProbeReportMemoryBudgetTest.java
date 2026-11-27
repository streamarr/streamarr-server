package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probe Report Memory Budget Tests")
class ProbeReportMemoryBudgetTest {

  private final ProbeReportMemoryBudget budget = new ProbeReportMemoryBudget(10);
  private final List<String> grantedLater = new ArrayList<>();

  @Test
  @DisplayName("Should give a probe report its room at once when it fits the budget exactly")
  void shouldGiveProbeReportItsRoomAtOnceWhenItFitsBudgetExactly() {
    var reservation = reserve("exact", 10);

    assertThat(reservation.isGrantedAtOnce()).isTrue();
    assertThat(grantedLater).isEmpty();
  }

  @Test
  @DisplayName("Should make a probe report wait when it exceeds the free room by one byte")
  void shouldMakeProbeReportWaitWhenItExceedsFreeRoomByOneByte() {
    reserve("holder", 6);

    var waiting = reserve("waiting", 5);

    assertThat(waiting.isGrantedAtOnce()).isFalse();
    assertThat(grantedLater).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give a waiting probe report its room when the room that it needs is released")
  void shouldGiveWaitingProbeReportItsRoomWhenRoomThatItNeedsIsReleased() {
    var holder = reserve("holder", 6);
    reserve("waiting", 5);

    holder.close();

    assertThat(grantedLater).containsExactly("waiting");
  }

  @Test
  @DisplayName(
      "Should make a later probe report wait behind an earlier one when only the later one fits")
  void shouldMakeLaterProbeReportWaitBehindEarlierOneWhenOnlyLaterOneFits() {
    var holder = reserve("holder", 6);
    reserve("earlier", 8);

    var later = reserve("later", 2);
    holder.close();

    assertThat(later.isGrantedAtOnce()).isFalse();
    assertThat(grantedLater).containsExactly("earlier", "later");
  }

  @Test
  @DisplayName("Should keep a probe report waiting when released room leaves it one byte short")
  void shouldKeepProbeReportWaitingWhenReleasedRoomLeavesItOneByteShort() {
    var first = reserve("first", 5);
    reserve("second", 5);
    reserve("waiting", 6);

    first.close();

    assertThat(grantedLater).isEmpty();
  }

  @Test
  @DisplayName("Should give the next probe report its room when the one ahead of it stops waiting")
  void shouldGiveNextProbeReportItsRoomWhenOneAheadOfItStopsWaiting() {
    reserve("holder", 6);
    var earlier = reserve("earlier", 8);
    reserve("later", 2);

    earlier.close();

    assertThat(grantedLater).containsExactly("later");
  }

  @Test
  @DisplayName("Should release a probe report's room once when its reservation closes twice")
  void shouldReleaseProbeReportsRoomOnceWhenItsReservationClosesTwice() {
    var holder = reserve("holder", 6);
    reserve("other holder", 4);
    holder.close();

    holder.close();
    var waiting = reserve("waiting", 7);

    assertThat(waiting.isGrantedAtOnce()).isFalse();
  }

  @Test
  @DisplayName("Should hold no room for a probe report that stopped waiting before it got room")
  void shouldHoldNoRoomForProbeReportThatStoppedWaitingBeforeItGotRoom() {
    var holder = reserve("holder", 6);
    var gaveUp = reserve("gave up", 8);
    gaveUp.close();

    holder.close();
    var next = reserve("next", 10);

    assertThat(grantedLater).isEmpty();
    assertThat(next.isGrantedAtOnce()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 11})
  @DisplayName("Should refuse a reservation when no room could ever hold its probe report")
  void shouldRefuseReservationWhenNoRoomCouldEverHoldItsProbeReport(long bytes) {
    assertThatIllegalArgumentException().isThrownBy(() -> reserve("unfit", bytes));
  }

  @Test
  @DisplayName(
      "Should give a playback probe's report its room at once when it fits and only a larger"
          + " background probe's report waits")
  void shouldGivePlaybackProbesReportItsRoomAtOnceWhenItFitsAndOnlyLargerBackgroundReportWaits() {
    reserve("holder", 6);
    reserve("earlier background", 8);

    var playback = reserve(ProbeKind.PLAYBACK, "playback", 4);

    assertThat(playback.isGrantedAtOnce()).isTrue();
    assertThat(grantedLater).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give a waiting playback probe's report its room before a background probe's report"
          + " that asked earlier when room is released")
  void shouldGiveWaitingPlaybackProbesReportItsRoomBeforeBackgroundReportThatAskedEarlier() {
    var holder = reserve("holder", 6);
    reserve("earlier background", 5);
    reserve(ProbeKind.PLAYBACK, "later playback", 5);

    holder.close();

    assertThat(grantedLater).containsExactly("later playback", "earlier background");
  }

  @Test
  @DisplayName(
      "Should keep a background probe's report waiting when released room fits it and not the"
          + " playback probe's report that waits")
  void shouldKeepBackgroundProbesReportWaitingWhenReleasedRoomFitsItAndNotWaitingPlaybackReport() {
    var first = reserve("first holder", 5);
    reserve("second holder", 5);
    reserve("earlier background", 5);
    reserve(ProbeKind.PLAYBACK, "playback", 6);

    first.close();

    assertThat(grantedLater).isEmpty();
  }

  @Test
  @DisplayName(
      "Should make a background probe's report wait when it fits while a playback probe's report"
          + " waits")
  void shouldMakeBackgroundProbesReportWaitWhenItFitsWhilePlaybackProbesReportWaits() {
    var holder = reserve("holder", 6);
    reserve(ProbeKind.PLAYBACK, "playback", 8);

    var background = reserve("background", 2);
    holder.close();

    assertThat(background.isGrantedAtOnce()).isFalse();
    assertThat(grantedLater).containsExactly("playback", "background");
  }

  @Test
  @DisplayName(
      "Should make a later playback probe's report wait behind an earlier one when only the later"
          + " one fits")
  void shouldMakeLaterPlaybackProbesReportWaitBehindEarlierOneWhenOnlyLaterOneFits() {
    var holder = reserve("holder", 6);
    reserve(ProbeKind.PLAYBACK, "earlier", 8);

    var later = reserve(ProbeKind.PLAYBACK, "later", 2);
    holder.close();

    assertThat(later.isGrantedAtOnce()).isFalse();
    assertThat(grantedLater).containsExactly("earlier", "later");
  }

  @Test
  @DisplayName(
      "Should give the reports of background probes and keyframe packet scans their room in the"
          + " order in which they asked")
  void shouldGiveReportsOfBackgroundProbesAndKeyframePacketScansTheirRoomInOrderInWhichTheyAsked() {
    var holder = reserve("holder", 10);
    reserve(ProbeKind.KEYFRAME_PACKET_SCAN, "earlier scan", 4);
    reserve(ProbeKind.BACKGROUND, "background", 4);
    reserve(ProbeKind.KEYFRAME_PACKET_SCAN, "later scan", 2);

    holder.close();

    assertThat(grantedLater).containsExactly("earlier scan", "background", "later scan");
  }

  @Test
  @DisplayName(
      "Should make a playback probe's report wait when a background probe's report holds the room"
          + " that it needs")
  void shouldMakePlaybackProbesReportWaitWhenBackgroundProbesReportHoldsRoomThatItNeeds() {
    reserve("holder", 6);

    var playback = reserve(ProbeKind.PLAYBACK, "playback", 5);

    assertThat(playback.isGrantedAtOnce()).isFalse();
    assertThat(grantedLater).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give a background probe's report its room when the playback probe's report ahead of"
          + " it stops waiting")
  void shouldGiveBackgroundProbesReportItsRoomWhenPlaybackProbesReportAheadOfItStopsWaiting() {
    reserve("holder", 6);
    var playback = reserve(ProbeKind.PLAYBACK, "playback", 8);
    reserve("background", 4);

    playback.close();

    assertThat(grantedLater).containsExactly("background");
  }

  /** Reserves room for the probe report of a background probe. */
  private ProbeReportMemoryBudget.Reservation reserve(String name, long bytes) {
    return reserve(ProbeKind.BACKGROUND, name, bytes);
  }

  private ProbeReportMemoryBudget.Reservation reserve(ProbeKind kind, String name, long bytes) {
    return budget.reserve(kind, bytes, () -> grantedLater.add(name));
  }
}
