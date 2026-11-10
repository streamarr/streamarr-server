package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Report Limits Tests")
class ProbeReportLimitsTest {

  private static final long MEBIBYTE = 1024L * 1024;

  @Test
  @DisplayName(
      "Should give a report call thirty seconds for a message and probe reports 64 MiB when"
          + " neither is configured")
  void
      shouldGiveReportCallThirtySecondsForMessageAndProbeReports64MebibytesWhenNeitherIsConfigured() {
    var limits = ProbeReportLimits.builder().build();

    assertThat(limits.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(limits.memoryBudgetBytes()).isEqualTo(64 * MEBIBYTE);
  }

  @Test
  @DisplayName("Should refuse a length limit when one array cannot hold a probe report that long")
  void shouldRefuseLengthLimitWhenOneArrayCannotHoldProbeReportThatLong() {
    var limits =
        ProbeReportLimits.builder()
            .lengthLimitBytes(Integer.MAX_VALUE + 1L)
            .memoryBudgetBytes(Integer.MAX_VALUE + 1L);

    assertThatIllegalArgumentException()
        .isThrownBy(limits::build)
        .withMessage("Worker probe report length limit must be at most 2147483647 bytes");
  }

  @Test
  @DisplayName("Should accept a length limit when one array can just hold a probe report that long")
  void shouldAcceptLengthLimitWhenOneArrayCanJustHoldProbeReportThatLong() {
    var limits =
        ProbeReportLimits.builder()
            .lengthLimitBytes(Integer.MAX_VALUE)
            .memoryBudgetBytes(Integer.MAX_VALUE)
            .build();

    assertThat(limits.lengthLimitBytes()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  @DisplayName("Should refuse the limits when the idle timeout is missing")
  void shouldRefuseLimitsWhenIdleTimeoutIsMissing() {
    var limits = ProbeReportLimits.builder();

    assertThatNullPointerException()
        .isThrownBy(() -> limits.idleTimeout(null))
        .withMessageContaining("idleTimeout");
  }
}
