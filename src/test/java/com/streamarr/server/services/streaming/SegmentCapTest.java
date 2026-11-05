package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.PeakSegmentBitRate.SegmentSize;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Segment Cap Tests")
class SegmentCapTest {

  private static final BigDecimal TEN_SECONDS = new BigDecimal("10.000000");

  // The producer reserves 128 bytes of heap for each sample (ADR 0037). Segment 1's 1,000 video
  // samples and 500 audio samples reserve 192,000 bytes beside their bytes.
  @ParameterizedTest
  @CsvSource({"134025727, false", "134025728, false", "134025729, true"})
  @DisplayName(
      "Should count each segment's video, its audio and their samples' heap cost against one cap")
  void shouldCountEachSegmentsVideoItsAudioAndTheirSamplesHeapCostAgainstOneCap(
      long videoBytes, boolean exceeded) {
    var video = List.of(segment(1_000, 24), segment(videoBytes - 1_000_000, 1_000));
    var audio = List.of(segment(500, 10), segment(1_000_000, 500));

    assertThat(SegmentCap.isExceededBy(video, audio)).isEqualTo(exceeded);
  }

  @ParameterizedTest
  @CsvSource({"134214528, false", "134214529, true"})
  @DisplayName("Should check the video alone when the copy carries no audio")
  void shouldCheckTheVideoAloneWhenTheCopyCarriesNoAudio(long videoBytes, boolean exceeded) {
    var video = List.of(segment(videoBytes, 25));

    assertThat(SegmentCap.isExceededBy(video, List.of())).isEqualTo(exceeded);
  }

  private static SegmentSize segment(long bytes, long samples) {
    return new SegmentSize(BigDecimal.valueOf(bytes), TEN_SECONDS, BigInteger.valueOf(samples));
  }
}
