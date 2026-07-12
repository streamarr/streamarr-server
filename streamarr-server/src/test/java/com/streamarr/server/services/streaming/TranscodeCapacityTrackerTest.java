package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class TranscodeCapacityTrackerTest {

  @Test
  @DisplayName("Should cap concurrent claims and reuse released capacity")
  void shouldCapConcurrentClaimsAndReuseReleasedCapacity() {
    var tracker = new TranscodeCapacityTracker();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();

    assertThat(tracker.claimUpTo(first, 3, 2)).isEqualTo(2);
    assertThat(tracker.claimUpTo(first, 1, 2)).isEqualTo(2);
    assertThat(tracker.claimUpTo(second, 1, 2)).isZero();
    tracker.markActive(first);
    assertThat(tracker.activeClaims())
        .extracting(TranscodeCapacityTracker.ActiveClaim::sessionId)
        .containsExactly(first);

    tracker.release(first);

    assertThat(tracker.claimExact(second, 2, 2)).isTrue();
  }

  @Test
  @DisplayName("Should release only active claims during stale reconciliation")
  void shouldReleaseOnlyActiveClaimsDuringStaleReconciliation() {
    var tracker = new TranscodeCapacityTracker();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    assertThat(tracker.claimExact(first, 1, 2)).isTrue();
    assertThat(tracker.claimExact(first, 2, 2)).isFalse();

    assertThat(tracker.activeClaim(first)).isEmpty();
    tracker.releaseActive(new TranscodeCapacityTracker.ActiveClaim(first, 1));
    assertThat(tracker.claimExact(second, 2, 2)).isFalse();

    tracker.markActive(first);
    tracker.releaseActive(tracker.activeClaim(first).orElseThrow());
    assertThat(tracker.claimExact(second, 2, 2)).isTrue();
  }

  @Test
  @DisplayName("Should retain capacity when a restart overtakes stale active reconciliation")
  void shouldRetainCapacityWhenRestartOvertakesStaleActiveReconciliation() {
    var tracker = new TranscodeCapacityTracker();
    var restartingSession = UUID.randomUUID();
    var otherSession = UUID.randomUUID();
    assertThat(tracker.claimExact(restartingSession, 1, 1)).isTrue();
    tracker.markActive(restartingSession);
    var staleClaim = tracker.activeClaim(restartingSession).orElseThrow();

    assertThat(tracker.claimExact(restartingSession, 1, 1)).isTrue();
    tracker.releaseActive(staleClaim);

    assertThat(tracker.claimExact(otherSession, 1, 1)).isFalse();
  }

  @Test
  @DisplayName("Should retain a completed restart against stale active reconciliation")
  void shouldRetainCompletedRestartAgainstStaleActiveReconciliation() {
    var tracker = new TranscodeCapacityTracker();
    var restartingSession = UUID.randomUUID();
    var otherSession = UUID.randomUUID();
    assertThat(tracker.claimExact(restartingSession, 1, 1)).isTrue();
    tracker.markActive(restartingSession);
    var staleClaim = tracker.activeClaim(restartingSession).orElseThrow();

    assertThat(tracker.claimExact(restartingSession, 1, 1)).isTrue();
    tracker.markActive(restartingSession);
    var restartedClaim = tracker.activeClaim(restartingSession).orElseThrow();
    assertThat(restartedClaim.epoch()).isNotEqualTo(staleClaim.epoch());
    tracker.releaseActive(staleClaim);

    assertThat(tracker.activeClaim(restartingSession)).contains(restartedClaim);
    assertThat(tracker.claimExact(otherSession, 1, 1)).isFalse();
  }

  @Test
  @DisplayName("Should reject nonpositive capacity values")
  void shouldRejectNonpositiveCapacityValues() {
    var tracker = new TranscodeCapacityTracker();

    assertThatThrownBy(() -> tracker.claimUpTo(UUID.randomUUID(), 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tracker.claimExact(UUID.randomUUID(), 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
