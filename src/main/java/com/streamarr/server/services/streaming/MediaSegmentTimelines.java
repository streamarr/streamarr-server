package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamSession;

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
   * only when the keyframes are known and open every segment, so a copy session always has them.
   */
  static MediaSegmentTimeline of(StreamSession session, StreamingProperties properties) {
    var probe = session.getMediaProbe();
    var grid = of(probe, properties);
    if (!session.getTranscodeDecision().transcodeMode().copiesVideo()) {
      return grid;
    }

    if (!(probe.keyframes() instanceof KeyframeKnowledge.Known(var positions))) {
      throw new IllegalStateException("A stream copy session needs the source's keyframes");
    }

    if (!(KeyframeSegmentBoundaries.forStreamCopy(positions, grid.targetSegmentDurationSeconds())
        instanceof StreamCopySegments.Starts starts)) {
      throw new IllegalStateException("A stream copy session needs a keyframe in every segment");
    }

    return grid.openedAt(positions, starts);
  }
}
