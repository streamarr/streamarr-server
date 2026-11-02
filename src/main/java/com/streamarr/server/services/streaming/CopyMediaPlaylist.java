package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import java.math.BigDecimal;
import lombok.NonNull;

/**
 * The media segments that a stream copy's media playlist lists and the sum of the EXTINF durations
 * that it writes for them, over which a measured copy's average bandwidth is counted (ADR 0054).
 */
public record CopyMediaPlaylist(int segmentCount, @NonNull BigDecimal durationSeconds) {

  /** The media playlist of a source that a stream copy cannot open at its keyframes. */
  static final CopyMediaPlaylist UNKNOWN = new CopyMediaPlaylist(0, BigDecimal.ZERO);

  /** The media playlist that a stream copy of the source would list. */
  static CopyMediaPlaylist of(@NonNull MediaProbe source, @NonNull StreamingProperties properties) {
    return MediaSegmentTimelines.streamCopyOf(source, properties)
        .map(CopyMediaPlaylist::of)
        .orElse(UNKNOWN);
  }

  static CopyMediaPlaylist of(@NonNull MediaSegmentTimeline timeline) {
    var durations = ExtinfDurations.of(timeline);
    return new CopyMediaPlaylist(
        durations.size(), durations.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  /** Whether the playlist lists a segment and lasts longer than zero seconds. */
  boolean isKnown() {
    return segmentCount > 0 && durationSeconds.signum() > 0;
  }
}
