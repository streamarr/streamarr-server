package com.streamarr.server.config.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway benchmark: background (secondary artwork) slots on the TMDB gate. */
@Tag("UnitTest")
@DisplayName("POC paced TMDB gate tests")
class PacedTmdbGateTest {

  @Test
  @DisplayName("Should wait until core work drains when background work yields")
  void shouldWaitUntilCoreWorkDrainsWhenBackgroundWorkYields() {
    var gate = new PacedTmdbGate(100, Duration.ZERO);
    for (var i = 0; i < 30; i++) {
      gate.reserveNanos();
    }

    var coreBacklog = gate.backlogNanos();
    var waited = CompletableFuture.supplyAsync(() -> gate.awaitBackgroundSlot(true)).join();

    assertThat(coreBacklog).isGreaterThan(Duration.ofMillis(250).toNanos());
    assertThat(waited).isGreaterThanOrEqualTo(coreBacklog - Duration.ofMillis(20).toNanos());
  }

  @Test
  @DisplayName("Should let core work reserve ahead of a yielding background request")
  void shouldLetCoreWorkReserveAheadOfAYieldingBackgroundRequest() throws Exception {
    var gate = new PacedTmdbGate(100, Duration.ZERO);
    for (var i = 0; i < 10; i++) {
      gate.reserveNanos();
    }

    var background = CompletableFuture.supplyAsync(() -> gate.awaitBackgroundSlot(true));
    Thread.sleep(50);
    var coreWait = gate.reserveNanos();
    var backgroundWait = background.join();

    // The core request reserved after the background request asked, yet was served first.
    assertThat(backgroundWait).isGreaterThan(coreWait);
  }

  @Test
  @DisplayName("Should not delay the next core request when background work takes an idle slot")
  void shouldNotDelayTheNextCoreRequestWhenBackgroundWorkTakesAnIdleSlot() throws Exception {
    var gate = new PacedTmdbGate(10, Duration.ofSeconds(1));
    Thread.sleep(300);

    var backgroundWait = gate.awaitBackgroundSlot(true);
    var coreWait = gate.reserveNanos();

    assertThat(backgroundWait).isLessThan(Duration.ofMillis(20).toNanos());
    assertThat(coreWait).isZero();
  }

  @Test
  @DisplayName("Should leave queued FIFO background slots out of the core backlog")
  void shouldLeaveQueuedFifoBackgroundSlotsOutOfTheCoreBacklog() throws Exception {
    var gate = new PacedTmdbGate(10, Duration.ZERO);
    gate.reserveNanos();
    var background =
        java.util.stream.IntStream.range(0, 4)
            .mapToObj(_ -> CompletableFuture.runAsync(() -> gate.awaitBackgroundSlot(false)))
            .toList();
    Thread.sleep(50);

    assertThat(gate.backlogNanos()).isGreaterThan(Duration.ofMillis(300).toNanos());
    assertThat(gate.coreBacklogNanos()).isLessThan(Duration.ofMillis(100).toNanos());
    background.forEach(CompletableFuture::join);
    assertThat(gate.backgroundRequests()).isEqualTo(4);
  }
}
