package com.streamarr.server.domain.streaming;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.NonNull;

/**
 * The media segments a variant's playlist advertises, numbered from zero on the grid of the target
 * segment duration: segment {@code N}'s interval starts at {@code N × target}, where the target
 * counts in whole seconds.
 */
public sealed interface MediaSegmentTimeline {

  Duration targetSegmentDuration();

  int mediaSegmentCount();

  Duration mediaSegmentDuration(int index);

  /**
   * Where a job attempt that starts at this segment seeks with sub-second precision, when the
   * attempt must start at a source keyframe rather than at the segment's whole-second start.
   */
  Optional<Duration> keyframeSeekPosition(int index);

  default int targetSegmentDurationSeconds() {
    return (int) targetSegmentDuration().toSeconds();
  }

  default boolean advertises(int index) {
    return index >= 0 && index < mediaSegmentCount();
  }

  default int mediaSegmentStartSeconds(int index) {
    return index * targetSegmentDurationSeconds();
  }

  /**
   * The media segment that holds the position: the last segment that starts at or before it. A
   * position at or past the last segment's end lies in the last segment.
   */
  default int mediaSegmentIndexAt(@NonNull Duration position) {
    var lastIndex = mediaSegmentCount() - 1;
    var end = Duration.ZERO;
    for (int index = 0; index < lastIndex; index++) {
      end = end.plus(mediaSegmentDuration(index));
      if (position.compareTo(end) < 0) {
        return index;
      }
    }

    return Math.max(lastIndex, 0);
  }

  /**
   * The media segment that starts the given distance before segment {@code index}, counted in whole
   * target segment durations and at least one segment back. The index falls below zero when the
   * distance reaches past the first media segment.
   */
  default int mediaSegmentIndexBefore(int index, @NonNull Duration distance) {
    var segmentsBack = distance.toSeconds() / targetSegmentDurationSeconds();
    return index - Math.max(1, (int) segmentsBack);
  }

  /**
   * How long the grid slots are that these consecutive media segments, from segment {@code
   * firstIndex} on, open in: one target segment duration for each segment, because each segment
   * opens in a slot of its own.
   */
  default Duration slotSpan(int firstIndex, int segmentCount) {
    return targetSegmentDuration().multipliedBy(segmentCount);
  }

  /** Where the media segment ends: the sum of its own and every earlier segment's duration. */
  default Duration mediaSegmentEnd(int index) {
    return IntStream.rangeClosed(0, index)
        .mapToObj(this::mediaSegmentDuration)
        .reduce(Duration.ZERO, Duration::plus);
  }

  /**
   * Segment {@code N} covers media time {@code [N × target, (N + 1) × target)}, and the last
   * segment ends at the media duration, counted in whole milliseconds.
   */
  record PlainGrid(@NonNull Duration mediaDuration, @NonNull Duration targetSegmentDuration)
      implements MediaSegmentTimeline {

    @Override
    public int mediaSegmentCount() {
      return Math.toIntExact(Math.ceilDiv(mediaDuration.toMillis(), targetSegmentMillis()));
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      var remainingMillis = mediaDuration.toMillis() - index * targetSegmentMillis();
      return Duration.ofMillis(Math.min(targetSegmentMillis(), remainingMillis));
    }

    @Override
    public Optional<Duration> keyframeSeekPosition(int index) {
      return Optional.empty();
    }

    /**
     * This grid's segments as a stream copy cuts them at the source keyframes that open each one. A
     * keyframe that presents at or after the media end opens no segment.
     */
    public StreamCopy openedAt(
        @NonNull KeyframePositions positions, StreamCopySegments.@NonNull Starts starts) {
      var mediaEnd = Duration.ofMillis(mediaDuration.toMillis());
      var openingKeyframes = new ArrayList<OpeningKeyframe>();
      for (var keyframeIndex : starts.openingKeyframeIndexes()) {
        var keyframe = positions.keyframes().get(keyframeIndex);
        var presentationTime = positions.timeOf(keyframe.presentationTime(), RoundingMode.FLOOR);
        if (presentationTime.compareTo(mediaEnd) >= 0) {
          break;
        }

        // Rounding the decode time up keeps a seek from reaching back to the keyframe before.
        var decodeTime = positions.timeOf(keyframe.decodeTime(), RoundingMode.CEILING);
        openingKeyframes.add(new OpeningKeyframe(keyframeIndex, presentationTime, decodeTime));
      }

      return new StreamCopy(mediaEnd, targetSegmentDuration, openingKeyframes);
    }

    private long targetSegmentMillis() {
      return targetSegmentDurationSeconds() * 1000L;
    }
  }

  /**
   * An encode's segments on the plain grid when the source's keyframes are known. A job attempt
   * after segment 0 reads from the last keyframe that presents at least half a second before its
   * first segment starts, and the worker decodes up to that start without encoding
   * (streamarr/streamarr-server#502).
   */
  record KeyframeSeekingGrid(
      @NonNull PlainGrid grid,
      @NonNull KeyframePositions positions,
      @NonNull DemuxerSeekTime seekTime)
      implements MediaSegmentTimeline {

    private static final Duration SEEK_MARGIN = Duration.ofMillis(500);

    @Override
    public Duration targetSegmentDuration() {
      return grid.targetSegmentDuration();
    }

    @Override
    public int mediaSegmentCount() {
      return grid.mediaSegmentCount();
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      return grid.mediaSegmentDuration(index);
    }

    /**
     * The chosen keyframe's time that the container's demuxer seeks by. An attempt reads from the
     * source's start when no keyframe presents early enough or the keyframe decodes before zero.
     */
    @Override
    public Optional<Duration> keyframeSeekPosition(int index) {
      if (index == 0) {
        return Optional.empty();
      }

      var latestPresentation =
          Duration.ofSeconds(mediaSegmentStartSeconds(index)).minus(SEEK_MARGIN);
      // Rounding up keeps a seek from reaching back to the keyframe before.
      var position =
          positions.keyframes().stream()
              .filter(keyframe -> presentsAtOrBefore(keyframe, latestPresentation))
              .reduce((earlier, later) -> later)
              .map(keyframe -> positions.timeOf(seekTime.ticksOf(keyframe), RoundingMode.CEILING))
              .filter(time -> !time.isNegative())
              .orElse(Duration.ZERO);
      return Optional.of(position);
    }

    // Rounding up keeps out a keyframe that presents a fraction of a nanosecond after the time.
    private boolean presentsAtOrBefore(KeyframePosition keyframe, Duration time) {
      return positions.timeOf(keyframe.presentationTime(), RoundingMode.CEILING).compareTo(time)
          <= 0;
    }
  }

  /**
   * A stream copy's segments, which open at source keyframes under ADR 0037's boundary rule.
   * Segment {@code N} opens at the presentation time of its keyframe and runs until the next
   * segment opens. Segment 0 opens at zero instead, because it also holds the media that presents
   * before its keyframe. The last segment runs to the media duration. Each segment's start stays on
   * the grid.
   */
  record StreamCopy(
      @NonNull Duration mediaDuration,
      @NonNull Duration targetSegmentDuration,
      @NonNull List<OpeningKeyframe> openingKeyframes)
      implements MediaSegmentTimeline {

    public StreamCopy {
      openingKeyframes = List.copyOf(openingKeyframes);
    }

    @Override
    public int mediaSegmentCount() {
      return openingKeyframes.size();
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      var end = mediaDuration;
      if (index + 1 < openingKeyframes.size()) {
        end = openingTime(index + 1);
      }

      return end.minus(openingTime(index));
    }

    /**
     * An attempt at segment 0 reads from the start of the source. A later attempt seeks to the
     * decode time of its segment's keyframe, which a container's demuxer seeks by, and never before
     * zero.
     */
    @Override
    public Optional<Duration> keyframeSeekPosition(int index) {
      if (index == 0) {
        return Optional.empty();
      }

      var decodeTime = openingKeyframes.get(index).decodeTime();
      if (decodeTime.isNegative()) {
        return Optional.of(Duration.ZERO);
      }

      return Optional.of(decodeTime);
    }

    private Duration openingTime(int index) {
      if (index == 0) {
        return Duration.ZERO;
      }

      return openingKeyframes.get(index).presentationTime();
    }
  }

  /**
   * A keyframe that opens a stream copy segment: its index in the source's keyframe positions,
   * which is also the index of the keyframe interval that it opens, and its times on the zero-based
   * timeline.
   */
  record OpeningKeyframe(
      int keyframeIndex, @NonNull Duration presentationTime, @NonNull Duration decodeTime) {}
}
