package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.PeakSegmentBitRate.SegmentSize;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * RFC 8216 §4.1: the peak segment bit rate is the largest bit rate of any run of consecutive
 * segments whose total duration lies between 0.5 and 1.5 times the target duration.
 */
@Tag("UnitTest")
@DisplayName("Peak Segment Bit Rate Tests")
class PeakSegmentBitRateTest {

  // Under a 6 s target, a 3 s run qualifies. The 3 s last segment alone runs at 8,000,000 b/s,
  // above the 4,000,000 b/s of the 9 s run that it ends.
  @Test
  @DisplayName(
      "Should count a run whose duration is exactly half the target when its bit rate is highest")
  void shouldCountARunWhoseDurationIsExactlyHalfTheTargetWhenItsBitRateIsHighest() {
    var segments =
        List.of(
            size(1_500_000, "6.000000"), size(1_500_000, "6.000000"), size(3_000_000, "3.000000"));

    assertThat(PeakSegmentBitRate.of(segments, 6)).isEqualTo(8_000_000L);
  }

  // Under a 6 s target, a 9 s run qualifies. The 1 s, 6 s and 2 s segments together run at
  // 3,600,000 bytes over 9 s, 3,200,000 b/s, above the 2,600,000 b/s of the 6 s and 2 s segments.
  @Test
  @DisplayName(
      "Should count a run whose duration is exactly one and a half times the target when its bit"
          + " rate is highest")
  void shouldCountARunWhoseDurationIsExactlyOneAndAHalfTimesTheTargetWhenItsBitRateIsHighest() {
    var segments =
        List.of(
            size(1_000_000, "1.000000"), size(600_000, "6.000000"), size(2_000_000, "2.000000"));

    assertThat(PeakSegmentBitRate.of(segments, 6)).isEqualTo(3_200_000L);
  }

  // A merged 9 s segment on 3 s slots makes the media playlist write a 9 s target, so runs last
  // 4.5 to 13.5 s. The 3 s segment then counts only with the 9 s one: 5,250,000 bytes over 12 s,
  // 3,500,000 b/s. Under the 3 s slot it would count alone at 8,000,000 b/s.
  @Test
  @DisplayName(
      "Should measure runs in the target duration the playlist writes when merged segments run"
          + " longer than the slot")
  void shouldMeasureRunsInTheWrittenTargetDurationWhenMergedSegmentsRunLongerThanTheSlot() {
    var segments =
        List.of(
            size(3_000_000, "3.000000"), size(2_250_000, "9.000000"), size(1_500_000, "6.000000"));

    assertThat(PeakSegmentBitRate.of(segments, 9)).isEqualTo(3_500_000L);
  }

  // Under a 6 s target no run of a 2 s playlist reaches 3 s. The whole playlist runs at 300,000
  // bytes over 2 s, 1,200,000 b/s, though its first segment alone runs at 1,600,000 b/s.
  @Test
  @DisplayName(
      "Should treat the whole playlist as one run when it lasts less than half the target duration")
  void shouldTreatTheWholePlaylistAsOneRunWhenItLastsLessThanHalfTheTargetDuration() {
    var segments = List.of(size(200_000, "1.000000"), size(100_000, "1.000000"));

    assertThat(PeakSegmentBitRate.of(segments, 6)).isEqualTo(1_200_000L);
  }

  // 1,000,001 bytes over 6 s are 1,333,334.67 b/s.
  @Test
  @DisplayName("Should round the peak up to a whole bit per second when it has a fraction")
  void shouldRoundThePeakUpToAWholeBitPerSecondWhenItHasAFraction() {
    assertThat(PeakSegmentBitRate.of(List.of(size(1_000_001, "6.000000")), 6))
        .isEqualTo(1_333_335L);
  }

  @Test
  @DisplayName("Should advertise at most the largest bit rate when the bytes exceed what it counts")
  void shouldAdvertiseAtMostTheLargestBitRateWhenTheBytesExceedWhatItCounts() {
    var segment =
        new SegmentSize(new BigDecimal(Long.MAX_VALUE), new BigDecimal("6.000000"), BigInteger.ONE);

    assertThat(PeakSegmentBitRate.of(List.of(segment), 6)).isEqualTo(Long.MAX_VALUE);
  }

  private static SegmentSize size(long bytes, String extinfSeconds) {
    return new SegmentSize(
        BigDecimal.valueOf(bytes), new BigDecimal(extinfSeconds), BigInteger.ONE);
  }
}
