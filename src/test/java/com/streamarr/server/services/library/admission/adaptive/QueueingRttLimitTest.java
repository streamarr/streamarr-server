package com.streamarr.server.services.library.admission.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.concurrency.limits.Limit;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway benchmark: C2's Vegas limit reads queueing, not service-time spread. */
@Tag("UnitTest")
@DisplayName("POC queueing round-trip limit tests")
class QueueingRttLimitTest {

  @Test
  @DisplayName("Should keep growing when round trips only vary and nothing queues")
  void shouldKeepGrowingWhenRoundTripsOnlyVaryAndNothingQueues() {
    var wholeTask = AdaptiveLimitAlgorithm.VEGAS.newLimit();
    var queueing = new QueueingRttLimit(AdaptiveLimitAlgorithm.VEGAS.newQueueingLimit());

    feed(wholeTask, 0, false, 2_000);
    feed(queueing, 0, true, 2_000);

    assertThat(wholeTask.getLimit()).isLessThan(10);
    assertThat(queueing.getLimit()).isGreaterThan(100);
  }

  @Test
  @DisplayName("Should shrink and stay down while tasks queue at the gate")
  void shouldShrinkAndStayDownWhileTasksQueueAtTheGate() {
    var queueing = new QueueingRttLimit(AdaptiveLimitAlgorithm.VEGAS.newQueueingLimit());
    feed(queueing, 0, true, 2_000);
    var grown = queueing.getLimit();

    feed(queueing, TimeUnit.MILLISECONDS.toNanos(400), true, 5_000);

    assertThat(queueing.getLimit()).isLessThan(Math.min(10, grown / 10));
  }

  /** Stub-calibrated TMDB latency (78% ~45 ms, 22% ~117 ms, lognormal) plus the queueing time. */
  private static void feed(Limit limit, long waitNanos, boolean withWait, int samples) {
    var random = new SplittableRandom(1);
    for (var i = 0; i < samples; i++) {
      var serviceMs =
          random.nextDouble() < 0.78
              ? 44.9 * Math.exp(0.19 * random.nextGaussian())
              : 116.8 * Math.exp(0.13 * random.nextGaussian());
      var rtt = (long) (serviceMs * 1e6) + waitNanos;
      var inflight = limit.getLimit();
      if (withWait) {
        ScopedValue.where(QueueingDelay.SAMPLE_WAIT, waitNanos)
            .run(() -> limit.onSample(0, rtt, inflight, false));
      } else {
        limit.onSample(0, rtt, inflight, false);
      }
    }
  }
}
