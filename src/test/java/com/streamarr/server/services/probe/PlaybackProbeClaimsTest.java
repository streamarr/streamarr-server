package com.streamarr.server.services.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.MutableClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Playback Probe Claims Tests")
class PlaybackProbeClaimsTest {

  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PlaybackProbeClaims claims = new PlaybackProbeClaims(clock, meterRegistry);
  private final UUID mediaFileId = UUID.randomUUID();

  @Test
  @DisplayName(
      "Should time the wait from the playback request to the claim when the scheduler claims the"
          + " probe that playback waits for")
  void shouldTimeTheWaitFromThePlaybackRequestToTheClaimWhenTheSchedulerClaimsTheProbe() {
    claims.waitStarted(mediaFileId, clock.instant());
    clock.advance(Duration.ofSeconds(4));

    try (var _ = claims.claimed(mediaFileId)) {
      assertThat(unitWait().count()).isOne();
      assertThat(unitWait().totalTime(TimeUnit.SECONDS)).isEqualTo(4);
    }
  }

  @Test
  @DisplayName(
      "Should time one wait from the earliest request when two viewers wait for the same probe")
  void shouldTimeOneWaitFromTheEarliestRequestWhenTwoViewersWaitForTheSameProbe() {
    claims.waitStarted(mediaFileId, clock.instant());
    clock.advance(Duration.ofSeconds(1));
    claims.waitStarted(mediaFileId, clock.instant());
    clock.advance(Duration.ofSeconds(2));

    try (var _ = claims.claimed(mediaFileId)) {
      assertThat(unitWait().count()).isOne();
      assertThat(unitWait().totalTime(TimeUnit.SECONDS)).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("Should time no wait when the scheduler claims a probe that no playback waits for")
  void shouldTimeNoWaitWhenTheSchedulerClaimsAProbeThatNoPlaybackWaitsFor() {
    claims.waitStarted(mediaFileId, clock.instant()).close();
    claims.waitStarted(mediaFileId, clock.instant()).close();

    try (var _ = claims.claimed(mediaFileId)) {
      assertThat(unitWait().count()).isZero();
    }
  }

  @Test
  @DisplayName("Should keep timing the wait when one of two viewers stops waiting")
  void shouldKeepTimingTheWaitWhenOneOfTwoViewersStopsWaiting() {
    claims.waitStarted(mediaFileId, clock.instant()).close();
    claims.waitStarted(mediaFileId, clock.instant());

    try (var _ = claims.claimed(mediaFileId)) {
      assertThat(unitWait().count()).isOne();
    }
  }

  @Test
  @DisplayName(
      "Should time a later viewer's wait when an earlier viewer of a deferred probe stops waiting")
  void shouldTimeALaterViewersWaitWhenAnEarlierViewerOfADeferredProbeStopsWaiting() {
    var earlier = claims.waitStarted(mediaFileId, clock.instant());
    claims.claimed(mediaFileId).close();
    clock.advance(Duration.ofSeconds(5));
    claims.waitStarted(mediaFileId, clock.instant());
    clock.advance(Duration.ofSeconds(1));
    earlier.close();
    clock.advance(Duration.ofSeconds(2));

    try (var _ = claims.claimed(mediaFileId)) {
      assertThat(unitWait().count()).isEqualTo(2);
      assertThat(unitWait().max(TimeUnit.SECONDS)).isEqualTo(3);
    }
  }

  @Test
  @DisplayName(
      "Should count each claimed playback probe as in progress when its execution has"
          + " not ended")
  void shouldCountEachClaimedPlaybackProbeAsInProgressWhenItsExecutionHasNotEnded() {
    var first = claims.claimed(mediaFileId);
    var second = claims.claimed(UUID.randomUUID());
    assertThat(claims.inProgress()).isEqualTo(2);

    first.close();
    second.close();

    assertThat(claims.inProgress()).isZero();
  }

  @Test
  @DisplayName("Should count a claimed playback probe once when its execution ends twice")
  void shouldCountAClaimedPlaybackProbeOnceWhenItsExecutionEndsTwice() {
    var claimed = claims.claimed(mediaFileId);
    claims.claimed(UUID.randomUUID());

    claimed.close();
    claimed.close();

    assertThat(claims.inProgress()).isOne();
  }

  private Timer unitWait() {
    return meterRegistry.get("streamarr.playback.probe_unit_wait").timer();
  }
}
