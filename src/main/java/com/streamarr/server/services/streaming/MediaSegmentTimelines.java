package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.DemuxerSeekTime;
import com.streamarr.server.domain.streaming.ExactSegmentStarts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.properties.StreamingProperties;
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
   * A stream copy's segments open at the source keyframes, on the segment list that they build. The
   * transcode decision copies the video only when the keyframes are known and open at least one
   * segment, so a copy session always has them. An encode's attempts seek to the source keyframes
   * when they are known.
   */
  static MediaSegmentTimeline of(StreamSession session, StreamingProperties properties) {
    var probe = session.getMediaProbe();
    if (!session.getTranscodeDecision().transcodeMode().copiesVideo()) {
      return encodeOf(probe, properties);
    }

    return streamCopyOf(probe, properties)
        .orElseThrow(
            () -> new IllegalStateException("A stream copy session needs the source's keyframes"));
  }

  /**
   * Each segment's exact start, which every job attempt of the stream session carries: a stream
   * copy's keyframe ticks, or a plain grid's whole seconds when it merges its final slot. Empty
   * when the session's segments start on the plain grid unmerged (ADR 0037, segment list).
   */
  static Optional<ExactSegmentStarts> exactSegmentStartsOf(
      StreamSession session, MediaSegmentTimeline timeline) {
    return switch (timeline) {
      case MediaSegmentTimeline.PlainGrid grid -> grid.exactStarts();
      case MediaSegmentTimeline.KeyframeSeekingGrid seekingGrid -> seekingGrid.grid().exactStarts();
      case MediaSegmentTimeline.StreamCopy copy -> copyStartsOf(session, copy);
    };
  }

  private static Optional<ExactSegmentStarts> copyStartsOf(
      StreamSession session, MediaSegmentTimeline.StreamCopy copy) {
    if (!(session.getMediaProbe().keyframes() instanceof KeyframeKnowledge.Known(var positions))) {
      return Optional.empty();
    }

    return Optional.of(copy.exactStartsIn(positions));
  }

  // Without the keyframes, the worker finds the keyframe that an attempt reads from in the source.
  private static MediaSegmentTimeline encodeOf(MediaProbe probe, StreamingProperties properties) {
    var grid = of(probe, properties);
    if (!(probe.keyframes() instanceof KeyframeKnowledge.Known(var positions))) {
      return grid;
    }

    return new MediaSegmentTimeline.KeyframeSeekingGrid(
        grid, positions, DemuxerSeekTime.of(probe.containerFormat()));
  }

  /**
   * The segments of a stream copy of the source, which open at its keyframes, or empty when the
   * keyframes are not known or the server cannot place them.
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
