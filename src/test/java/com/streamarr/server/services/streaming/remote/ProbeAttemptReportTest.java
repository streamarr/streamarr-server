package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Attempt Report Tests")
class ProbeAttemptReportTest {

  private static final ProbeAttemptResult RESULT = ProbeAttemptResult.getDefaultInstance();

  @Test
  @DisplayName("Should hold no interval bytes when the report gives only keyframe positions")
  void shouldHoldNoIntervalBytesWhenReportGivesOnlyKeyframePositions() {
    assertThat(new ProbeAttemptReport(RESULT, List.of()).streamMeasurementParts()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a report when its result or one of its part lists is missing")
  void shouldRefuseReportWhenItsResultOrOneOfItsPartListsIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ProbeAttemptReport(null, List.of()))
        .withMessageContaining("result");
    assertThatNullPointerException()
        .isThrownBy(() -> new ProbeAttemptReport(RESULT, null))
        .withMessageContaining("keyframePositionsParts");
    assertThatNullPointerException()
        .isThrownBy(() -> new ProbeAttemptReport(RESULT, List.of(), null))
        .withMessageContaining("streamMeasurementParts");
  }
}
