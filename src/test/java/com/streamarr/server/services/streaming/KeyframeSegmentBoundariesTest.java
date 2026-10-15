package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Keyframe Segment Boundaries Tests")
class KeyframeSegmentBoundariesTest {

  // The recorded times count ticks of the fMP4 video track's timescale, where the producer grouped
  // them. Stored positions count ticks of the source's time base. In a stream copy, FFmpeg's MP4
  // muxer takes the source time base's denominator as the timescale and doubles it until it reaches
  // 10,000. Each source tick is then a whole number of track ticks, so both give the same segments.
  private static final String RECORDED_FIXTURES =
      "/keyframe-segment-boundaries/adr-0037-copy-fixtures.json";

  private record RecordedFixtures(List<RecordedFixture> fixtures) {}

  private record RecordedFixture(
      String name,
      int periodSeconds,
      int videoTimescale,
      List<Long> keyframePresentationTimes,
      List<Long> segmentStarts,
      Integer skippedSegmentNumber) {

    @Override
    public String toString() {
      return name;
    }
  }

  @ParameterizedTest
  @MethodSource("recordedFixturesWithoutASkip")
  @DisplayName("Should start each segment where the producer started it in ADR 0037's fixtures")
  void shouldStartEachSegmentWhereTheProducerStartedItInAdr0037sFixtures(RecordedFixture fixture) {
    var positions = positions(fixture.videoTimescale(), fixture.keyframePresentationTimes());

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, fixture.periodSeconds());

    assertThat(segments).isEqualTo(startsAt(fixture.segmentStarts()));
  }

  @Test
  @DisplayName(
      "Should find the interval without a keyframe where the producer skipped a segment number")
  void shouldFindTheIntervalWithoutAKeyframeWhereTheProducerSkippedASegmentNumber()
      throws IOException {
    var fixture = recordedFixture("10-copy-gop-exceeds-period");
    var positions = positions(fixture.videoTimescale(), fixture.keyframePresentationTimes());

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, fixture.periodSeconds());

    assertThat(fixture.skippedSegmentNumber()).isEqualTo(2);
    assertThat(segments).isEqualTo(new StreamCopySegments.IntervalWithoutKeyframe(2));
  }

  @Test
  @DisplayName("Should find the first interval empty when the first keyframe lies in a later one")
  void shouldFindTheFirstIntervalEmptyWhenTheFirstKeyframeLiesInALaterOne() {
    var positions = positions(1_000, List.of(6_500L, 12_000L));

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(new StreamCopySegments.IntervalWithoutKeyframe(0));
  }

  @Test
  @DisplayName("Should open no segment at a keyframe that presents before zero")
  void shouldOpenNoSegmentAtAKeyframeThatPresentsBeforeZero() {
    var positions = positions(1_000, List.of(-2_000L, 0L, 6_000L));

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(startsAt(List.of(0L, 6_000L)));
  }

  @Test
  @DisplayName("Should find the first interval empty when every keyframe presents before zero")
  void shouldFindTheFirstIntervalEmptyWhenEveryKeyframePresentsBeforeZero() {
    var positions = positions(1_000, List.of(-4_000L, -2_000L));

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(new StreamCopySegments.IntervalWithoutKeyframe(0));
  }

  @Test
  @DisplayName(
      "Should refuse the positions when a keyframe presents before the keyframe decoded before it")
  void shouldRefuseThePositionsWhenAKeyframePresentsBeforeTheKeyframeDecodedBeforeIt() {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(
                List.of(
                    new KeyframePosition(0, 0),
                    new KeyframePosition(4_000, 1_000),
                    new KeyframePosition(3_000, 2_000),
                    new KeyframePosition(6_000, 3_000)))
            .build();

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(new StreamCopySegments.PresentationTimeRegressed(2));
  }

  @ParameterizedTest
  @ValueSource(longs = {Long.MAX_VALUE / 1000, Long.MIN_VALUE / 1000})
  @DisplayName("Should refuse the positions when a keyframe time in seconds overflows a long")
  void shouldRefuseThePositionsWhenAKeyframeTimeInSecondsOverflowsALong(long presentationTime) {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(List.of(new KeyframePosition(presentationTime, 0)))
            .build();

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(new StreamCopySegments.PresentationTimeOutOfRange(0));
  }

  @ParameterizedTest
  @ValueSource(longs = {Long.MAX_VALUE / 1000, Long.MIN_VALUE / 1000})
  @DisplayName(
      "Should refuse the positions when an opening keyframe's decode time in seconds overflows a"
          + " long")
  void shouldRefuseThePositionsWhenAnOpeningKeyframesDecodeTimeInSecondsOverflowsALong(
      long decodeTime) {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(144, decodeTime)))
            .build();

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(new StreamCopySegments.DecodeTimeOutOfRange(1));
  }

  @Test
  @DisplayName("Should ignore the decode time of a keyframe that opens no segment")
  void shouldIgnoreTheDecodeTimeOfAKeyframeThatOpensNoSegment() {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(
                List.of(new KeyframePosition(0, 0), new KeyframePosition(24, Long.MAX_VALUE)))
            .build();

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments)
        .isEqualTo(new StreamCopySegments.Starts(List.of(new KeyframePosition(0, 0))));
  }

  @Test
  @DisplayName("Should place a keyframe by its time in seconds when the time base is not 1/n")
  void shouldPlaceAKeyframeByItsTimeInSecondsWhenTheTimeBaseIsNotOneOverN() {
    // In a 1001/24000 time base, 144 ticks are 6.006 s and 143 ticks are 5.964 s.
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(143, 143)))
            .build();

    var segments = KeyframeSegmentBoundaries.forStreamCopy(positions, 6);

    assertThat(segments).isEqualTo(startsAt(List.of(0L)));
  }

  @Test
  @DisplayName("Should reject a period that is not positive")
  void shouldRejectAPeriodThatIsNotPositive() {
    var positions = positions(1_000, List.of(0L));

    assertThatThrownBy(() -> KeyframeSegmentBoundaries.forStreamCopy(positions, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject missing keyframe positions")
  void shouldRejectMissingKeyframePositions() {
    assertThatThrownBy(() -> KeyframeSegmentBoundaries.forStreamCopy(null, 6))
        .isInstanceOf(NullPointerException.class);
  }

  static Stream<RecordedFixture> recordedFixturesWithoutASkip() throws IOException {
    return recordedFixtures().stream().filter(fixture -> fixture.skippedSegmentNumber() == null);
  }

  private static RecordedFixture recordedFixture(String name) throws IOException {
    return recordedFixtures().stream()
        .filter(fixture -> fixture.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<RecordedFixture> recordedFixtures() throws IOException {
    try (var json = KeyframeSegmentBoundariesTest.class.getResourceAsStream(RECORDED_FIXTURES)) {
      assertThat(json).as(RECORDED_FIXTURES).isNotNull();
      return JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build()
          .readValue(json, RecordedFixtures.class)
          .fixtures();
    }
  }

  private static StreamCopySegments.Starts startsAt(List<Long> presentationTimes) {
    return new StreamCopySegments.Starts(keyframesAt(presentationTimes));
  }

  // The rule reads presentation times only. The recordings hold no decode times, and these
  // keyframes carry none of their own, so each position repeats its presentation time.
  private static KeyframePositions positions(int timescale, List<Long> presentationTimes) {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator(timescale)
        .source(KeyframePositionSource.CONTAINER_INDEX)
        .keyframes(keyframesAt(presentationTimes))
        .build();
  }

  private static List<KeyframePosition> keyframesAt(List<Long> presentationTimes) {
    return presentationTimes.stream().map(time -> new KeyframePosition(time, time)).toList();
  }
}
