package com.streamarr.server.services.library.admission.adaptive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.limit.VegasLimit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway benchmark variant C: a zero round trip must never reach VegasLimit. */
@Tag("UnitTest")
@DisplayName("POC positive round-trip limit tests")
class PositiveRttLimitTest {

  private static Limit vegasOfOne() {
    return VegasLimit.newBuilder().initialLimit(1).alpha(1).beta(2).maxConcurrency(10).build();
  }

  private static SimpleLimiter<Void> frozenClockLimiter(Limit limit) {
    return SimpleLimiter.newBuilder().limit(limit).nanoClock(() -> 42L).build();
  }

  @Test
  @DisplayName(
      "Should keep the permit forever when VegasLimit receives a zero round trip unguarded")
  void shouldKeepPermitForeverWhenVegasLimitReceivesZeroRoundTripUnguarded() {
    var limiter = frozenClockLimiter(vegasOfOne());

    var thrown = catchThrowable(() -> limiter.acquire(null).orElseThrow().onSuccess());

    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rtt");
    assertThat(limiter.acquire(null)).isEmpty();
  }

  @Test
  @DisplayName("Should release the permit without a sample when the round trip is zero")
  void shouldReleasePermitWithoutSampleWhenRoundTripIsZero() {
    var guarded = new PositiveRttLimit(vegasOfOne());
    var limiter = frozenClockLimiter(guarded);

    limiter.acquire(null).orElseThrow().onSuccess();
    limiter.acquire(null).orElseThrow().onDropped();

    assertThat(limiter.acquire(null)).isPresent();
    assertThat(guarded.skippedSamples()).isEqualTo(2);
    assertThat(guarded.getLimit()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should pass a positive round trip to the wrapped limit")
  void shouldPassPositiveRoundTripToWrappedLimit() {
    var guarded =
        new PositiveRttLimit(
            VegasLimit.newBuilder().initialLimit(10).alpha(1).beta(2).maxConcurrency(10).build());

    // The first sample only sets Vegas's no-load round trip; the drop then lowers the limit.
    guarded.onSample(0, 1_000_000, 10, false);
    guarded.onSample(0, 1_000_000, 10, true);

    assertThat(guarded.skippedSamples()).isZero();
    assertThat(guarded.getLimit()).isEqualTo(9);
  }
}
