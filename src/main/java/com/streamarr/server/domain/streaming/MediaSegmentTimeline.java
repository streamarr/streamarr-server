package com.streamarr.server.domain.streaming;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
      for (var keyframe : starts.openingKeyframes()) {
        var presentationTime = positions.timeOf(keyframe.presentationTime(), RoundingMode.FLOOR);
        if (presentationTime.compareTo(mediaEnd) >= 0) {
          break;
        }

        // Rounding the decode time up keeps a seek from reaching back to the keyframe before.
        var decodeTime = positions.timeOf(keyframe.decodeTime(), RoundingMode.CEILING);
        openingKeyframes.add(new OpeningKeyframe(presentationTime, decodeTime));
      }

      return new StreamCopy(mediaEnd, targetSegmentDuration, openingKeyframes);
    }

    private long targetSegmentMillis() {
      return targetSegmentDurationSeconds() * 1000L;
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

  /** The times of a keyframe that opens a stream copy segment, on the zero-based timeline. */
  record OpeningKeyframe(@NonNull Duration presentationTime, @NonNull Duration decodeTime) {}
}
