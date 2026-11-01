package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamSession;
import java.util.Optional;

/**
 * The one derivation of a probed media file's media segment timeline, shared by the media playlist
 * and every job attempt so that both advertise the same media segments.
 */
final class MediaSegmentTimelines {

  private MediaSegmentTimelines() {}

  static MediaSegmentTimeline.PlainGrid of(MediaProbe probe, StreamingProperties properties) {
    return new MediaSegmentTimeline.PlainGrid(probe.duration(), properties.targetSegmentDuration());
  }

  /**
   * A stream copy's segments open at the source keyframes. The transcode decision copies the video
   * only when the keyframes are known and open every grid interval except possibly the last, so a
   * copy session always has them.
   */
  static MediaSegmentTimeline of(StreamSession session, StreamingProperties properties) {
    var probe = session.getMediaProbe();
    if (!session.getTranscodeDecision().transcodeMode().copiesVideo()) {
      return of(probe, properties);
    }

    return streamCopyOf(probe, properties)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "A stream copy session needs the source's keyframes and a keyframe in every"
                        + " segment"));
  }

  /**
   * The segments of a stream copy of the source, which open at its keyframes, or empty when the
   * keyframes are not known or a grid interval holds none.
   */
  static Optional<MediaSegmentTimeline.StreamCopy> streamCopyOf(
      MediaProbe probe, StreamingProperties properties) {
    if (!(probe.keyframes() instanceof KeyframeKnowledge.Known(var positions))) {
      return Optional.empty();
    }

    var grid = of(probe, properties);
    if (!(KeyframeSegmentBoundaries.forStreamCopy(positions, grid.targetSegmentDurationSeconds())
        instanceof StreamCopySegments.Starts starts)) {
      return Optional.empty();
    }

    return Optional.of(grid.openedAt(positions, starts));
  }
}
