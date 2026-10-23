package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Progress Report Interval Tests")
class ProgressReportIntervalTest {

  private static final Duration INTERVAL = Duration.ofSeconds(30);
  private static final Instant START = Instant.parse("2026-10-16T12:00:00Z");

  private final ProgressReportInterval interval = new ProgressReportInterval(INTERVAL, START);

  @Test
  @DisplayName(
      "Should hold the first report when less than one interval has passed since the start")
  void shouldHoldTheFirstReportWhenLessThanOneIntervalHasPassedSinceTheStart() {
    assertThat(interval.tryTakeReport(START)).isFalse();
    assertThat(interval.tryTakeReport(START.plus(INTERVAL).minusMillis(1))).isFalse();
    assertThat(interval.tryTakeReport(START.plus(INTERVAL))).isTrue();
  }

  @Test
  @DisplayName("Should hold the next report for another interval when a report was taken")
  void shouldHoldTheNextReportForAnotherIntervalWhenAReportWasTaken() {
    var reportedAt = START.plus(INTERVAL).plusSeconds(5);
    interval.tryTakeReport(reportedAt);

    assertThat(interval.tryTakeReport(reportedAt.plus(INTERVAL).minusMillis(1))).isFalse();
    assertThat(interval.tryTakeReport(reportedAt.plus(INTERVAL))).isTrue();
  }
}
