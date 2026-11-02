package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.DecimalSeconds;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.IntStream;
import lombok.NonNull;

/** The EXTINF duration that a media playlist writes for each media segment of a timeline. */
final class ExtinfDurations {

  private static final int EXTINF_DIGITS = 6;

  private ExtinfDurations() {}

  /** Each segment's duration in seconds, rounded half up to microseconds, in segment order. */
  static List<BigDecimal> of(@NonNull MediaSegmentTimeline timeline) {
    return IntStream.range(0, timeline.mediaSegmentCount())
        .mapToObj(
            index ->
                DecimalSeconds.of(timeline.mediaSegmentDuration(index))
                    .setScale(EXTINF_DIGITS, RoundingMode.HALF_UP))
        .toList();
  }

  /**
   * The EXT-X-TARGETDURATION that a media playlist writes for these EXTINF durations of the
   * timeline. RFC 8216 §4.3.3.1: every EXTINF, rounded to the nearest integer, must not exceed the
   * target duration. A stream copy's segment can run longer than the target segment duration.
   */
  static int targetDurationSeconds(
      @NonNull MediaSegmentTimeline timeline, @NonNull List<BigDecimal> extinfDurations) {
    var targetDuration = timeline.targetSegmentDurationSeconds();
    for (var extinf : extinfDurations) {
      targetDuration =
          Math.max(targetDuration, extinf.setScale(0, RoundingMode.HALF_UP).intValueExact());
    }

    return targetDuration;
  }
}
