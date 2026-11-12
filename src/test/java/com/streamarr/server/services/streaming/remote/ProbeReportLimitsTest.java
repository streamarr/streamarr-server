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
  @DisplayName(
      "Should limit the result of a probe report to 128 KiB when no result length limit is"
          + " configured")
  void shouldLimitResultOfProbeReportTo128KibibytesWhenNoResultLengthLimitIsConfigured() {
    var limits = ProbeReportLimits.builder().build();

    assertThat(limits.resultLengthLimitBytes()).isEqualTo(128 * 1024);
  }

  @Test
  @DisplayName(
      "Should refuse a result length limit when it is longer than the probe report length limit")
  void shouldRefuseResultLengthLimitWhenItIsLongerThanProbeReportLengthLimit() {
    var limits =
        ProbeReportLimits.builder().lengthLimitBytes(MEBIBYTE).resultLengthLimitBytes(MEBIBYTE + 1);

    assertThatIllegalArgumentException()
        .isThrownBy(limits::build)
        .withMessage(
            "Worker probe report result length limit must be at most the probe report length"
                + " limit");
  }

  @Test
  @DisplayName(
      "Should refuse a result length limit when it is shorter than the 48 KiB of a result's titles"
          + " and chapters")
  void shouldRefuseResultLengthLimitWhenItIsShorterThan48KibibytesOfResultsTitlesAndChapters() {
    var limits = ProbeReportLimits.builder().resultLengthLimitBytes(48L * 1024 - 1);

    assertThatIllegalArgumentException()
        .isThrownBy(limits::build)
        .withMessage("Worker probe report result length limit must be at least 49152 bytes");
  }

  @Test
  @DisplayName(
      "Should accept a result length limit when it equals the 48 KiB of a result's titles and"
          + " chapters")
  void shouldAcceptResultLengthLimitWhenItEquals48KibibytesOfResultsTitlesAndChapters() {
    var limits = ProbeReportLimits.builder().resultLengthLimitBytes(48L * 1024).build();

    assertThat(limits.resultLengthLimitBytes()).isEqualTo(48L * 1024);
  }

  @Test
  @DisplayName("Should accept a result length limit when it equals the probe report length limit")
  void shouldAcceptResultLengthLimitWhenItEqualsProbeReportLengthLimit() {
    var limits =
        ProbeReportLimits.builder()
            .lengthLimitBytes(MEBIBYTE)
            .resultLengthLimitBytes(MEBIBYTE)
            .build();

    assertThat(limits.resultLengthLimitBytes()).isEqualTo(MEBIBYTE);
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
