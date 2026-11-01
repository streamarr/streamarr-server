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
}
