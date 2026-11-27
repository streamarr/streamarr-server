package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Bitrate Scan Chunks Tests")
class BitrateScanChunksTest {

  private static final long SECOND = 90_000;
  private static final long FOUR_GIB = 4L << 30;

  @Test
  @DisplayName("Should span the file's duration share of one gibibyte when the keyframes allow it")
  void shouldSpanTheFilesDurationShareOfOneGibibyteWhenTheKeyframesAllowIt() {
    // 100 seconds of a 4 GiB file put one gibibyte in 25 seconds.
    var file = file(100, FOUR_GIB, Optional.of(Duration.ofSeconds(100)));

    assertThat(BitrateScanChunks.next(file, 0))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(0, 24));
    assertThat(BitrateScanChunks.next(file, 24))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(24, 24));
  }

  @Test
  @DisplayName("Should run the last chunk to the end of the file when few intervals remain")
  void shouldRunTheLastChunkToTheEndOfTheFileWhenFewIntervalsRemain() {
    var file = file(100, FOUR_GIB, Optional.of(Duration.ofSeconds(100)));

    assertThat(BitrateScanChunks.next(file, 96))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(96, 4));
  }

  @Test
  @DisplayName("Should hold one interval when a single interval spans more than the target")
  void shouldHoldOneIntervalWhenASingleIntervalSpansMoreThanTheTarget() {
    // Each second of this file holds 8 GiB.
    var file = file(10, 80L << 30, Optional.of(Duration.ofSeconds(10)));

    assertThat(BitrateScanChunks.next(file, 3)).isEqualTo(new BitrateScanChunks.PlannedChunk(3, 1));
  }

  @Test
  @DisplayName("Should stop at 4,096 intervals when the file is small for its duration")
  void shouldStopAt4096IntervalsWhenTheFileIsSmallForItsDuration() {
    var file = file(5_000, 3, Optional.of(Duration.ofSeconds(5_000)));

    assertThat(BitrateScanChunks.next(file, 0))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(0, 4_096));
    assertThat(BitrateScanChunks.next(file, 4_096))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(4_096, 904));
  }

  @ParameterizedTest(name = "limit={0}, measured streams={1}")
  @MethodSource("measuredKeyframeIntervalLimits")
  @DisplayName(
      "Should hold as many keyframes as keep the intervals of every measured stream within the"
          + " measured keyframe interval limit, and at least one, when the file is small for its"
          + " duration")
  void shouldHoldAsManyKeyframesAsKeepEveryMeasuredStreamWithinLimitWhenFileIsSmallForItsDuration(
      int measuredKeyframeIntervalLimit, int measuredStreamCount, int expectedKeyframes) {
    // Small for its duration, so the duration share of one gibibyte never ends a chunk.
    var file =
        fileBuilder(5_000, 3, Optional.of(Duration.ofSeconds(5_000)))
            .measuredStreamCount(measuredStreamCount)
            .measuredKeyframeIntervalLimit(measuredKeyframeIntervalLimit)
            .build();

    assertThat(BitrateScanChunks.next(file, 0))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(0, expectedKeyframes));
  }

  private static Stream<Arguments> measuredKeyframeIntervalLimits() {
    return Stream.of(
        arguments(1_000, 1, 1_000),
        arguments(1_000, 4, 250),
        // One more keyframe would make 1,002 intervals.
        arguments(1_000, 3, 333),
        arguments(999, 4, 249),
        // A chunk holds at least one keyframe, although its three intervals exceed the limit.
        arguments(2, 3, 1),
        arguments(8_191, 2, 4_095),
        arguments(8_192, 2, 4_096),
        // The limit allows more keyframes than one chunk ever holds.
        arguments(1_000_000, 2, 4_096));
  }

  @Test
  @DisplayName(
      "Should span the file's duration share of one gibibyte when the measured keyframe interval"
          + " limit allows more keyframes")
  void shouldSpanFilesDurationShareOfOneGibibyteWhenMeasuredKeyframeIntervalLimitAllowsMore() {
    var file =
        fileBuilder(100, FOUR_GIB, Optional.of(Duration.ofSeconds(100)))
            .measuredStreamCount(2)
            .measuredKeyframeIntervalLimit(50)
            .build();

    assertThat(BitrateScanChunks.next(file, 0))
        .isEqualTo(new BitrateScanChunks.PlannedChunk(0, 24));
  }

  @ParameterizedTest(name = "container duration={0}")
  @MethodSource("durations")
  @DisplayName(
      "Should take the target span from the container's duration, or from the last keyframe when"
          + " the duration is unknown")
  void shouldTakeTheTargetSpanFromTheContainersDurationOrTheLastKeyframe(
      Optional<Duration> containerDuration, int expectedIntervals) {
    // One gibibyte of this 4 GiB file spans a quarter of its duration.
    var file = file(100, FOUR_GIB, containerDuration);

    assertThat(BitrateScanChunks.next(file, 0).keyframeIntervalCount())
        .isEqualTo(expectedIntervals);
  }

  private static Stream<Arguments> durations() {
    return Stream.of(
        // The last keyframe at 99 seconds puts the target at 24.75 seconds.
        arguments(Optional.empty(), 24), arguments(Optional.of(Duration.ofSeconds(200)), 49));
  }

  @Test
  @DisplayName("Should convert the container's duration into the keyframe positions' time base")
  void shouldConvertTheContainersDurationIntoTheKeyframePositionsTimeBase() {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1_001)
            .timeBaseDenominator(30_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            // One keyframe every 30 frames of 30000/1001 video, about one each second.
            .keyframes(
                IntStream.range(0, 100)
                    .mapToObj(index -> new KeyframePosition(index * 30L, index * 30L))
                    .toList())
            .build();
    var file =
        BitrateScanChunks.File.builder()
            .positions(positions)
            .size(FOUR_GIB)
            .containerDuration(Optional.of(Duration.ofMillis(100_100)))
            .measuredStreamCount(1)
            .measuredKeyframeIntervalLimit(1_000_000)
            .build();

    // A quarter of 3,000 ticks is 750 ticks, which 24 intervals of 30 ticks stay below.
    assertThat(BitrateScanChunks.next(file, 0).keyframeIntervalCount()).isEqualTo(24);
  }

  @Test
  @DisplayName("Should count the intervals the stored chunks cover from the first up to a gap")
  void shouldCountTheIntervalsTheStoredChunksCoverFromTheFirstUpToAGap() {
    assertThat(BitrateScanChunks.coveredIntervals(List.of())).isZero();
    assertThat(BitrateScanChunks.coveredIntervals(List.of(chunk(0, 10), chunk(10, 5))))
        .isEqualTo(15);
    assertThat(BitrateScanChunks.coveredIntervals(List.of(chunk(0, 10), chunk(12, 5))))
        .isEqualTo(10);
    assertThat(BitrateScanChunks.coveredIntervals(List.of(chunk(4, 10)))).isZero();
  }

  @Test
  @DisplayName("Should list the keyframes that open the chunk's intervals")
  void shouldListTheKeyframesThatOpenTheChunksIntervals() {
    var positions = file(10, FOUR_GIB, Optional.empty()).positions();

    assertThat(BitrateScanChunks.keyframesOf(positions, new BitrateScanChunks.PlannedChunk(2, 3)))
        .containsExactly(keyframe(2), keyframe(3), keyframe(4));
  }

  private static BitrateScanChunks.File file(
      int keyframes, long size, Optional<Duration> containerDuration) {
    return fileBuilder(keyframes, size, containerDuration).build();
  }

  /** A file whose one measured stream stays far below the measured keyframe interval limit. */
  private static BitrateScanChunks.File.FileBuilder fileBuilder(
      int keyframes, long size, Optional<Duration> containerDuration) {
    return BitrateScanChunks.File.builder()
        .measuredStreamCount(1)
        .measuredKeyframeIntervalLimit(1_000_000)
        .positions(
            KeyframePositions.builder()
                .streamIndex(0)
                .timeBaseNumerator(1)
                .timeBaseDenominator(90_000)
                .source(KeyframePositionSource.CONTAINER_INDEX)
                .keyframes(
                    IntStream.range(0, keyframes).mapToObj(index -> keyframe(index)).toList())
                .build())
        .size(size)
        .containerDuration(containerDuration);
  }

  private static KeyframePosition keyframe(int index) {
    return new KeyframePosition(index * SECOND, index * SECOND);
  }

  private static StoredBitrateScanChunk chunk(int firstKeyframeInterval, int intervals) {
    return StoredBitrateScanChunk.builder()
        .firstKeyframeInterval(firstKeyframeInterval)
        .keyframeIntervalCount(intervals)
        .streams(List.of())
        .build();
  }
}
