package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The media segments that a stream copy's media playlist lists, with the EXTINF duration and the
 * EXT-X-TARGETDURATION that it writes for them, over which a measured copy's average and peak
 * bandwidth are counted (ADR 0054). Each segment also takes its audio from its grid slots, which
 * run from its slot start to the next segment's (ADR 0037).
 */
public record CopyMediaPlaylist(@NonNull List<Segment> segments, int targetDurationSeconds) {

  /** The media playlist of a source that a stream copy cannot open at its keyframes. */
  static final CopyMediaPlaylist UNKNOWN = new CopyMediaPlaylist(List.of(), 0);

  public CopyMediaPlaylist {
    segments = List.copyOf(segments);
  }

  /**
   * A media segment that opens at the keyframe at {@code openingKeyframeIndex} of the source's
   * keyframe positions, whose grid slots start at {@code slotStartSeconds} of media time, and that
   * lasts {@code extinfSeconds} as the media playlist writes it.
   */
  @Builder
  public record Segment(
      int openingKeyframeIndex, int slotStartSeconds, @NonNull BigDecimal extinfSeconds) {}

  /** The media playlist that a stream copy of the source would list. */
  static CopyMediaPlaylist of(@NonNull MediaProbe source, @NonNull StreamingProperties properties) {
    return MediaSegmentTimelines.streamCopyOf(source, properties)
        .map(CopyMediaPlaylist::of)
        .orElse(UNKNOWN);
  }

  static CopyMediaPlaylist of(@NonNull MediaSegmentTimeline.StreamCopy timeline) {
    var durations = ExtinfDurations.of(timeline);
    var segments = new ArrayList<Segment>();
    for (var index = 0; index < durations.size(); index++) {
      segments.add(
          Segment.builder()
              .openingKeyframeIndex(timeline.openingKeyframes().get(index).keyframeIndex())
              .slotStartSeconds(timeline.mediaSegmentStartSeconds(index))
              .extinfSeconds(durations.get(index))
              .build());
    }

    return new CopyMediaPlaylist(
        segments, ExtinfDurations.targetDurationSeconds(timeline, durations));
  }

  int segmentCount() {
    return segments.size();
  }

  /** The sum of the EXTINF durations that the media playlist writes. */
  BigDecimal durationSeconds() {
    return segments.stream().map(Segment::extinfSeconds).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Whether the playlist lists a segment and lasts longer than zero seconds. */
  boolean isKnown() {
    return !segments.isEmpty() && durationSeconds().signum() > 0;
  }
}
