package com.streamarr.server.services.library.admission.adaptive;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.VegasLimit;

/**
 * The delay-based limit behind variant C, one per {@code poc.admission} value.
 *
 * <p>The library defaults suit request serving, not a batch walk: Gradient2's default {@code
 * minLimit} of 20 is a floor, and Vegas with its default alpha and beta cannot go below about six
 * on delay alone. Both start at four because about three to six files in flight saturate a 35
 * requests per second TMDB budget; {@code maxConcurrency} is only the ceiling the library requires.
 *
 * <p>Vegas on the whole-task round trip settles near four from service-time spread alone (TMDB
 * latency is bimodal, cast sizes vary), even with nothing queued anywhere. C2 therefore runs Vegas
 * on {@link QueueingRttLimit}'s queueing round trip by default ({@code poc.adaptive.vegas-rtt=
 * queueing}); {@code task} restores the whole-task round trip.
 */
public enum AdaptiveLimitAlgorithm {
  GRADIENT2("adaptive-gradient2") {
    @Override
    Limit newLimit() {
      return Gradient2Limit.newBuilder()
          .initialLimit(4)
          .minLimit(1)
          .maxConcurrency(1_000)
          .queueSize(1)
          .rttTolerance(1.0)
          .build();
    }
  },
  VEGAS("adaptive-vegas") {
    @Override
    Limit newLimit() {
      return VegasLimit.newBuilder().initialLimit(4).alpha(1).beta(2).maxConcurrency(1_000).build();
    }

    /**
     * No probing: {@link QueueingRttLimit} already supplies a fixed no-load round trip (the
     * smallest service time), and a probe would take a standing queue as the new baseline.
     */
    @Override
    Limit newQueueingLimit() {
      return VegasLimit.newBuilder()
          .initialLimit(4)
          .alpha(1)
          .beta(2)
          .maxConcurrency(1_000)
          .probeMultiplier(NO_PROBE)
          .build();
    }
  };

  private static final int NO_PROBE = 1_000_000;

  private final String admissionName;

  AdaptiveLimitAlgorithm(String admissionName) {
    this.admissionName = admissionName;
  }

  /** The {@code poc.admission} value that selects this algorithm. */
  public String admissionName() {
    return admissionName;
  }

  abstract Limit newLimit();

  /** The limit to wrap in a {@link QueueingRttLimit}. */
  Limit newQueueingLimit() {
    return newLimit();
  }
}
