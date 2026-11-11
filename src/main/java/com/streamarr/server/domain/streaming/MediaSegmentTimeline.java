package com.streamarr.server.domain.streaming;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import lombok.NonNull;

/**
 * The media segments a variant's playlist advertises, numbered from zero on slots of the target
 * segment duration, which counts in whole seconds. Slot {@code k} covers {@code [k × target, (k +
 * 1) × target)} of media time. On the plain grid, segment {@code N}'s interval is slot {@code N}. A
 * stream copy's segment list starts each segment at a slot of its own and can merge several slots
 * into one segment (ADR 0037).
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

  /**
   * The latest presentation time of the keyframe that an encoded attempt reads from when its first
   * segment starts at this second: half a second before that start, so that the attempt decodes the
   * pictures before it (streamarr/streamarr-server#502).
   */
  static Duration latestReadKeyframePresentation(int startSeconds) {
    return Duration.ofSeconds(startSeconds).minusMillis(500);
  }

  default int targetSegmentDurationSeconds() {
    return (int) targetSegmentDuration().toSeconds();
  }

  default boolean advertises(int index) {
    return index >= 0 && index < mediaSegmentCount();
  }

  /** The slot where the media segment's interval starts. */
  default int mediaSegmentStartSlot(int index) {
    return index;
  }

  default int mediaSegmentStartSeconds(int index) {
    return mediaSegmentStartSlot(index) * targetSegmentDurationSeconds();
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
   * segment ends at the media duration, counted in whole milliseconds. A final slot that the media
   * ends less than {@link #MINIMUM_LAST_SEGMENT_DURATION} into merges into the segment before it,
   * and every job attempt then carries the grid's {@link #exactStarts()} so that it groups that
   * slot into the last segment (ADR 0037, segment list).
   */
  record PlainGrid(@NonNull Duration mediaDuration, @NonNull Duration targetSegmentDuration)
      implements MediaSegmentTimeline {

    /**
     * The least time from the last segment's start to the media end: longer than a frame at the
     * slowest output frame rate and than a frame of every audio codec, so the last segment holds a
     * frame of every variant and rendition (ADR 0037, segment list).
     */
    public static final Duration MINIMUM_LAST_SEGMENT_DURATION = Duration.ofSeconds(2);

    private static final Fraction WHOLE_SECONDS = new Fraction(1, 1);

    @Override
    public int mediaSegmentCount() {
      if (mergesFinalSlot()) {
        return slotCount() - 1;
      }

      return slotCount();
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      var remainingMillis = mediaDuration.toMillis() - index * targetSegmentMillis();
      if (index == mediaSegmentCount() - 1) {
        return Duration.ofMillis(remainingMillis);
      }

      return Duration.ofMillis(Math.min(targetSegmentMillis(), remainingMillis));
    }

    /**
     * Each segment's start in whole seconds, in the time base 1/1, when the grid merges its final
     * slot, so that every job attempt groups that slot into the last segment; otherwise empty. The
     * plain grid has no stored keyframe time base to count its starts in (ADR 0037, segment list).
     */
    public Optional<ExactSegmentStarts> exactStarts() {
      if (!mergesFinalSlot()) {
        return Optional.empty();
      }

      var ticks =
          LongStream.range(0, mediaSegmentCount())
              .map(index -> index * targetSegmentDurationSeconds())
              .boxed()
              .toList();
      return Optional.of(new ExactSegmentStarts(ticks, WHOLE_SECONDS));
    }

    /** The slots that the media reaches into, counted in whole milliseconds. */
    int slotCount() {
      return Math.toIntExact(Math.ceilDiv(mediaDuration.toMillis(), targetSegmentMillis()));
    }

    private boolean mergesFinalSlot() {
      var finalSlotMillis = mediaDuration.toMillis() - (slotCount() - 1L) * targetSegmentMillis();
      return slotCount() > 1 && finalSlotMillis < MINIMUM_LAST_SEGMENT_DURATION.toMillis();
    }

    @Override
    public Optional<Duration> keyframeSeekPosition(int index) {
      return Optional.empty();
    }

    /**
     * This grid's segments as a stream copy cuts them at the source keyframes that open each one. A
     * keyframe that presents at or after the media end opens no segment, and neither does a later
     * segment's keyframe that presents less than {@link StreamCopy#MINIMUM_SEGMENT_DURATION} before
     * it.
     */
    public StreamCopy openedAt(
        @NonNull KeyframePositions positions, StreamCopySegments.@NonNull Starts starts) {
      var mediaEnd = Duration.ofMillis(mediaDuration.toMillis());
      var openingKeyframes = new ArrayList<OpeningKeyframe>();
      for (var keyframeIndex : starts.openingKeyframeIndexes()) {
        var keyframe = positions.keyframes().get(keyframeIndex);
        var presentationTime = positions.timeOf(keyframe.presentationTime(), RoundingMode.FLOOR);
        if (presentationTime.compareTo(mediaEnd) >= 0
            || opensTooLateSegment(openingKeyframes, positions, keyframe, mediaEnd)) {
          break;
        }

        // Rounding the decode time up keeps a seek from reaching back to the keyframe before.
        var decodeTime = positions.timeOf(keyframe.decodeTime(), RoundingMode.CEILING);
        openingKeyframes.add(new OpeningKeyframe(keyframeIndex, presentationTime, decodeTime));
      }

      return new StreamCopy(mediaEnd, targetSegmentDuration, openingKeyframes);
    }

    // Rounding up keeps out a keyframe that presents a fraction of a nanosecond too late.
    private static boolean opensTooLateSegment(
        List<OpeningKeyframe> openingKeyframes,
        KeyframePositions positions,
        KeyframePosition keyframe,
        Duration mediaEnd) {
      if (openingKeyframes.isEmpty()) {
        return false;
      }

      var latestStart = mediaEnd.minus(StreamCopy.MINIMUM_SEGMENT_DURATION);
      return positions
              .timeOf(keyframe.presentationTime(), RoundingMode.CEILING)
              .compareTo(latestStart)
          > 0;
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
          MediaSegmentTimeline.latestReadKeyframePresentation(mediaSegmentStartSeconds(index));
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
   * before its keyframe. The last segment runs to the media duration. Segment {@code N}'s interval
   * starts at the slot that holds its keyframe, and segment 0's at slot 0, so a slot that holds no
   * opening keyframe merges into the segment before it.
   */
  record StreamCopy(
      @NonNull Duration mediaDuration,
      @NonNull Duration targetSegmentDuration,
      @NonNull List<OpeningKeyframe> openingKeyframes)
      implements MediaSegmentTimeline {

    /**
     * The least time between two consecutive segment starts, and between the last start and the
     * media end: longer than a frame at the slowest output frame rate and than a frame of every
     * audio codec a segment carries, so that every segment holds a frame of every variant and
     * rendition (ADR 0037, segment list).
     */
    public static final Duration MINIMUM_SEGMENT_DURATION = Duration.ofSeconds(2);

    public StreamCopy {
      openingKeyframes = List.copyOf(openingKeyframes);
    }

    @Override
    public int mediaSegmentCount() {
      return openingKeyframes.size();
    }

    /**
     * Each segment's exact start in ticks of the keyframe positions that these segments opened at:
     * zero for segment 0, and the stored presentation tick of its opening keyframe for every later
     * segment.
     */
    public ExactSegmentStarts exactStartsIn(@NonNull KeyframePositions positions) {
      var ticks =
          IntStream.range(0, mediaSegmentCount())
              .mapToObj(index -> startTicks(index, positions))
              .toList();
      return new ExactSegmentStarts(
          ticks, new Fraction(positions.timeBaseNumerator(), positions.timeBaseDenominator()));
    }

    private long startTicks(int index, KeyframePositions positions) {
      if (index == 0) {
        return 0;
      }

      var keyframeIndex = openingKeyframes.get(index).keyframeIndex();
      return positions.keyframes().get(keyframeIndex).presentationTime();
    }

    @Override
    public int mediaSegmentStartSlot(int index) {
      if (index == 0) {
        return 0;
      }

      return slotAt(openingKeyframes.get(index).presentationTime());
    }

    /**
     * The latest media segment that ends at most one slot after the slot the given distance before
     * segment {@code index}'s start, counted in whole target segment durations, and at least one
     * segment back. On the plain grid that is the segment that starts at that slot. A long merged
     * segment that ends later than that is still being produced toward the requested segment, so it
     * is not the one to look for. The index falls below zero when no segment ends that early.
     */
    @Override
    public int mediaSegmentIndexBefore(int index, @NonNull Duration distance) {
      var latestEnd =
          mediaSegmentStartSlot(index) - distance.toSeconds() / targetSegmentDurationSeconds() + 1;
      var found = index;
      while (found > 0 && mediaSegmentStartSlot(found) > latestEnd) {
        found--;
      }

      return found - 1;
    }

    /**
     * How long the grid slots are that these consecutive media segments, from segment {@code
     * firstIndex} on, span: from the first one's start slot up to the next segment's, or for the
     * last segment up to the slot that holds the media end.
     */
    @Override
    public Duration slotSpan(int firstIndex, int segmentCount) {
      var slots = slotEnd(firstIndex + segmentCount - 1) - mediaSegmentStartSlot(firstIndex);
      return targetSegmentDuration().multipliedBy(slots);
    }

    private int slotEnd(int index) {
      if (index + 1 < mediaSegmentCount()) {
        return mediaSegmentStartSlot(index + 1);
      }

      return new PlainGrid(mediaDuration, targetSegmentDuration).slotCount();
    }

    private int slotAt(Duration time) {
      return Math.toIntExact(time.dividedBy(Duration.ofSeconds(targetSegmentDurationSeconds())));
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
