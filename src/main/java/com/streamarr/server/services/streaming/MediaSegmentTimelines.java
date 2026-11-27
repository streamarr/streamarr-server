package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.DemuxerSeekTime;
import com.streamarr.server.domain.streaming.ExactSegmentStarts;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.properties.StreamingProperties;
import java.time.Duration;
import java.util.Optional;

/**
 * The one derivation of a probed media file's media segment timeline, shared by the media playlist
 * and every job attempt so that both advertise the same media segments. Every timeline of a stream
 * session ends at the session's {@link StreamSession#getMediaEnd}, whether its variants copy or
 * encode.
 */
final class MediaSegmentTimelines {

  private MediaSegmentTimelines() {}

  static MediaSegmentTimeline.PlainGrid of(MediaProbe probe, StreamingProperties properties) {
    return gridEndingAt(probe.mediaEnd(), properties);
  }

  private static MediaSegmentTimeline.PlainGrid gridEndingAt(
      Duration mediaEnd, StreamingProperties properties) {
    return new MediaSegmentTimeline.PlainGrid(mediaEnd, properties.targetSegmentDuration());
  }

  /**
   * A stream copy's segments open at the source keyframes, on the segment list that they build. The
   * transcode decision copies the video only when the keyframes are known and open at least one
   * segment, so a copy session always has them. An encode's attempts seek to the source keyframes
   * when they are known.
   */
  static MediaSegmentTimeline of(StreamSession session, StreamingProperties properties) {
    var grid = gridEndingAt(session.getMediaEnd(), properties);
    return of(session.getMediaProbe(), grid, session.getTranscodeDecision());
  }

  static Optional<Duration> endOfCopiedAudioSegment(
      MediaProbe probe, TranscodeDecision decision, StreamingProperties properties) {
    if (decision.audioDecision().mode() != AudioMode.COPY) {
      return Optional.empty();
    }

    return probe
        .probedSessionAudioEnd()
        .filter(Duration::isPositive)
        .map(
            audioEnd -> {
              var timeline = of(probe, of(probe, properties), decision);
              return timeline.mediaSegmentEnd(timeline.mediaSegmentIndexAt(audioEnd.minusNanos(1)));
            });
  }

  private static MediaSegmentTimeline of(
      MediaProbe probe, MediaSegmentTimeline.PlainGrid grid, TranscodeDecision decision) {
    if (!decision.transcodeMode().copiesVideo()) {
      return encodeIn(probe, grid);
    }

    return streamCopyIn(probe, grid)
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
    return session.getMediaProbe().keyframes().usablePositions().map(copy::exactStartsIn);
  }

  // Without the keyframes, the worker reads from one period before the attempt's first segment.
  private static MediaSegmentTimeline encodeIn(
      MediaProbe probe, MediaSegmentTimeline.PlainGrid grid) {
    var positions = probe.keyframes().usablePositions();
    if (positions.isEmpty()) {
      return grid;
    }

    return new MediaSegmentTimeline.KeyframeSeekingGrid(
        grid, positions.get(), DemuxerSeekTime.of(probe.containerFormat()));
  }

  /**
   * The segments of a stream copy of the source, which open at its keyframes, or empty when the
   * keyframes are not known or the server cannot place them.
   */
  static Optional<MediaSegmentTimeline.StreamCopy> streamCopyOf(
      MediaProbe probe, StreamingProperties properties) {
    return streamCopyIn(probe, of(probe, properties));
  }

  private static Optional<MediaSegmentTimeline.StreamCopy> streamCopyIn(
      MediaProbe probe, MediaSegmentTimeline.PlainGrid grid) {
    return probe.keyframes().usablePositions().flatMap(positions -> streamCopyOf(positions, grid));
  }

  private static Optional<MediaSegmentTimeline.StreamCopy> streamCopyOf(
      KeyframePositions positions, MediaSegmentTimeline.PlainGrid grid) {
    if (!(KeyframeSegmentBoundaries.forStreamCopy(positions, grid.targetSegmentDurationSeconds())
        instanceof StreamCopySegments.Starts starts)) {
      return Optional.empty();
    }

    return Optional.of(grid.openedAt(positions, starts));
  }
}
