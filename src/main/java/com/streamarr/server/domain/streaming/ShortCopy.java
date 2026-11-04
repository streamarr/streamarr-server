package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/**
 * Where a stream copy's media stops before the end that its playlist advertises (ADR 0052): the
 * copy's last media segment, and that segment's end on the playlist's timeline. When every source
 * stream has ended by then, playback ends there normally.
 */
public record ShortCopy(
    int lastMediaSegmentIndex, @NonNull Duration mediaEnd, boolean everySourceStreamEnded) {

  /**
   * The short copy whose last media segment is this one. A source stream has ended by the copy's
   * end when its probed end time lies at most the grid slots of the next media segment past the
   * copy's media end. A stream whose end time the probe does not know counts as ended, because
   * nothing shows that it has media past the copy's end.
   */
  public static ShortCopy endingWith(
      int lastMediaSegmentIndex,
      @NonNull MediaSegmentTimeline timeline,
      @NonNull MediaProbe probe) {
    var mediaEnd = timeline.mediaSegmentEnd(lastMediaSegmentIndex);
    var latestStreamEnd = mediaEnd.plus(timeline.slotSpan(lastMediaSegmentIndex + 1, 1));
    var everySourceStreamEnded =
        probe.streams().stream()
            .flatMap(stream -> stream.endTime().stream())
            .allMatch(end -> end.compareTo(latestStreamEnd) <= 0);
    return new ShortCopy(lastMediaSegmentIndex, mediaEnd, everySourceStreamEnded);
  }
}
