package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Deferral Warnings Tests")
class ProbeDeferralWarningsTest {

  private static final Duration INTERVAL = Duration.ofMinutes(1);
  private static final long START = 1_000L;

  private final ProbeDeferralWarnings warnings = new ProbeDeferralWarnings(INTERVAL);

  @Test
  @DisplayName("Should warn about the first deferred probe when no warning was due before")
  void shouldWarnAboutTheFirstDeferredProbeWhenNoWarningWasDueBefore() {
    assertThat(warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START)).contains(1);
  }

  @Test
  @DisplayName("Should hold the warning when probes defer again within the interval")
  void shouldHoldTheWarningWhenProbesDeferAgainWithinTheInterval() {
    warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START);

    assertThat(warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START + 1)).isEmpty();
    assertThat(
            warnings.tryTakeDeferredCount(
                ProbeRefusal.WORKERS_BUSY, START + INTERVAL.toNanos() - 1))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should report every probe deferred since the last warning when the interval has passed")
  void shouldReportEveryProbeDeferredSinceTheLastWarningWhenTheIntervalHasPassed() {
    warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START);
    warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START + 1);
    warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START + 2);

    assertThat(warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START + INTERVAL.toNanos()))
        .contains(3);
    assertThat(
            warnings.tryTakeDeferredCount(
                ProbeRefusal.WORKERS_BUSY, START + INTERVAL.toNanos() + 1))
        .isEmpty();
  }

  @Test
  @DisplayName("Should warn about each reason on its own when probes defer for different reasons")
  void shouldWarnAboutEachReasonOnItsOwnWhenProbesDeferForDifferentReasons() {
    warnings.tryTakeDeferredCount(ProbeRefusal.WORKERS_BUSY, START);

    assertThat(warnings.tryTakeDeferredCount(ProbeRefusal.NO_COMPATIBLE_WORKER, START + 1))
        .contains(1);
  }
}
