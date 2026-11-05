package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Media segment timeline")
class MediaSegmentTimelineTest {

  private static final Duration SIX_SECONDS = Duration.ofSeconds(6);

  @ParameterizedTest
  @CsvSource({
    "PT2H, 1200",
    "PT2M5.5S, 21",
    "PT12S, 2",
    "PT12.0004S, 2",
    "PT3S, 1",
    "PT0.001S, 1",
    "PT0.000999999S, 0",
    "PT0S, 0"
  })
  @DisplayName(
      "Should count media segments over the whole milliseconds of the media when sizing the"
          + " timeline")
  void shouldCountMediaSegmentsOverTheWholeMillisecondsOfTheMediaWhenSizingTheTimeline(
      Duration mediaDuration, int expectedCount) {
    var timeline = new MediaSegmentTimeline.PlainGrid(mediaDuration, SIX_SECONDS);

    assertThat(timeline.mediaSegmentCount()).isEqualTo(expectedCount);
  }

  @Test
  @DisplayName("Should end the last media segment at the media duration when it runs short")
  void shouldEndTheLastMediaSegmentAtTheMediaDurationWhenItRunsShort() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.parse("PT2M5.5S"), SIX_SECONDS);

    assertThat(timeline.mediaSegmentDuration(0)).isEqualTo(SIX_SECONDS);
    assertThat(timeline.mediaSegmentDuration(19)).isEqualTo(SIX_SECONDS);
    assertThat(timeline.mediaSegmentDuration(20)).isEqualTo(Duration.ofMillis(5_500));
  }

  // The grid counts whole milliseconds, so a position in the media's last fraction of a millisecond
  // still lies in the last segment.
  @ParameterizedTest
  @CsvSource({"PT0S, 0", "PT5.999S, 0", "PT6S, 1", "PT2M0.0004S, 20", "PT2M5.5004S, 20"})
  @DisplayName("Should find the media segment that holds a position on the timeline")
  void shouldFindTheMediaSegmentThatHoldsAPositionOnTheTimeline(
      Duration position, int expectedIndex) {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.parse("PT2M5.5009S"), SIX_SECONDS);

    assertThat(timeline.mediaSegmentIndexAt(position)).isEqualTo(expectedIndex);
  }

  @Test
  @DisplayName("Should start each media segment on the zero-based grid when locating it")
  void shouldStartEachMediaSegmentOnTheZeroBasedGridWhenLocatingIt() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofHours(2), SIX_SECONDS);

    assertThat(timeline.mediaSegmentStartSeconds(0)).isZero();
    assertThat(timeline.mediaSegmentStartSeconds(900)).isEqualTo(5_400);
  }

  @ParameterizedTest
  @CsvSource({"PT24S, 46", "PT25S, 46", "PT6S, 49", "PT5S, 49", "PT0S, 49"})
  @DisplayName(
      "Should count whole target segment durations back, and at least one segment, when finding a"
          + " media segment before another")
  void shouldCountWholeTargetSegmentDurationsBackWhenFindingAMediaSegmentBeforeAnother(
      Duration distance, int expectedIndex) {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofHours(2), SIX_SECONDS);

    assertThat(timeline.mediaSegmentIndexBefore(50, distance)).isEqualTo(expectedIndex);
  }

  @Test
  @DisplayName(
      "Should find a media segment before the first one when the distance reaches past the start")
  void shouldFindAMediaSegmentBeforeTheFirstOneWhenTheDistanceReachesPastTheStart() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofHours(2), SIX_SECONDS);

    assertThat(timeline.mediaSegmentIndexBefore(2, Duration.ofSeconds(24))).isEqualTo(-2);
  }

  // The last segment of 2 m 5.5 s, segment 20, lasts 5.5 s but opens in a whole 6 s slot.
  @ParameterizedTest
  @CsvSource({"0, 1, PT6S", "19, 2, PT12S", "20, 1, PT6S"})
  @DisplayName(
      "Should span one whole target segment duration for each segment when measuring their grid"
          + " slots")
  void shouldSpanOneWholeTargetSegmentDurationForEachSegmentWhenMeasuringTheirGridSlots(
      int firstIndex, int segmentCount, Duration expectedSpan) {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.parse("PT2M5.5S"), SIX_SECONDS);

    assertThat(timeline.slotSpan(firstIndex, segmentCount)).isEqualTo(expectedSpan);
  }

  @Test
  @DisplayName("Should reject finding a media segment before another without a distance")
  void shouldRejectFindingAMediaSegmentBeforeAnotherWithoutADistance() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofHours(2), SIX_SECONDS);

    assertThatNullPointerException()
        .isThrownBy(() -> timeline.mediaSegmentIndexBefore(2, null))
        .withMessageContaining("distance");
  }

  @Test
  @DisplayName("Should advertise only the indices inside the timeline when asked about an index")
  void shouldAdvertiseOnlyTheIndicesInsideTheTimelineWhenAskedAboutAnIndex() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(60), SIX_SECONDS);

    assertThat(timeline.advertises(0)).isTrue();
    assertThat(timeline.advertises(9)).isTrue();
    assertThat(timeline.advertises(10)).isFalse();
    assertThat(timeline.advertises(-1)).isFalse();
  }

  @Test
  @DisplayName("Should keep whole seconds of the target when it carries a fraction")
  void shouldKeepWholeSecondsOfTheTargetWhenItCarriesAFraction() {
    var timeline =
        new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(13), Duration.ofMillis(6_500));

    assertThat(timeline.targetSegmentDurationSeconds()).isEqualTo(6);
    assertThat(timeline.mediaSegmentCount()).isEqualTo(3);
    assertThat(timeline.mediaSegmentDuration(2)).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("Should run each stream copy segment from its keyframe to the next one")
  void shouldRunEachStreamCopySegmentFromItsKeyframeToTheNextOne() {
    var timeline = streamCopyOver(Duration.ofSeconds(24), 0, 6_500, 12_000, 18_000);

    assertThat(timeline.mediaSegmentCount()).isEqualTo(4);
    assertThat(durations(timeline))
        .containsExactly(
            Duration.ofMillis(6_500),
            Duration.ofMillis(5_500),
            Duration.ofSeconds(6),
            Duration.ofSeconds(6));
    assertThat(timeline.targetSegmentDurationSeconds()).isEqualTo(6);
  }

  // At 6 s slots, keyframes at 0, 10, 20 and 30 s open segments in slots 0, 1, 3 and 5. The slots
  // [12 s, 18 s) and [24 s, 30 s) hold no keyframe and merge into the segment before each.
  @Test
  @DisplayName("Should start each merged stream copy segment at its listed slot")
  void shouldStartEachMergedStreamCopySegmentAtItsListedSlot() {
    var timeline = streamCopyOver(Duration.ofSeconds(36), 0, 10_000, 20_000, 30_000);

    assertThat(timeline.segmentStartSlots()).containsExactly(0, 1, 3, 5);
    assertThat(IntStream.range(0, 4).map(timeline::mediaSegmentStartSeconds))
        .containsExactly(0, 6, 18, 30);
    assertThat(durations(timeline))
        .containsExactly(
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(6));
  }

  @Test
  @DisplayName("Should start the first stream copy segment at slot 0 when its keyframe lies later")
  void shouldStartTheFirstStreamCopySegmentAtSlotZeroWhenItsKeyframeLiesLater() {
    var timeline = streamCopyOver(Duration.ofSeconds(24), 13_000, 19_000);

    assertThat(timeline.segmentStartSlots()).containsExactly(0, 3);
    assertThat(durations(timeline)).containsExactly(Duration.ofSeconds(19), Duration.ofSeconds(5));
  }

  // The 37 s media holds seven 6 s slots. The last segment opens in slot 5 and spans slots 5 and 6.
  @ParameterizedTest
  @CsvSource({"0, 1, PT6S", "1, 1, PT12S", "0, 2, PT18S", "2, 2, PT24S", "3, 1, PT12S"})
  @DisplayName("Should span every slot of each merged segment when measuring their grid slots")
  void shouldSpanEverySlotOfEachMergedSegmentWhenMeasuringTheirGridSlots(
      int firstIndex, int segmentCount, Duration expectedSpan) {
    var timeline = streamCopyOver(Duration.ofSeconds(37), 0, 10_000, 20_000, 30_000);

    assertThat(timeline.slotSpan(firstIndex, segmentCount)).isEqualTo(expectedSpan);
  }

  // Keyframes every 10 s open segments in slots 0, 1, 3, 5, 6, 8, 10 and 11 of 6 s. From segment
  // 6's slot 10, 24 s reach back to slot 6, which segment 4 opens, and 12 s to slot 8.
  @ParameterizedTest
  @CsvSource({"6, PT24S, 4", "6, PT12S, 5", "6, PT0S, 5", "7, PT6S, 6", "1, PT12S, -1"})
  @DisplayName(
      "Should count slots back, and at least one segment, when finding a merged segment before"
          + " another")
  void shouldCountSlotsBackWhenFindingAMergedSegmentBeforeAnother(
      int index, Duration distance, int expectedIndex) {
    var timeline =
        streamCopyOver(
            Duration.ofSeconds(72), 0, 10_000, 20_000, 30_000, 40_000, 50_000, 60_000, 70_000);

    assertThat(timeline.mediaSegmentIndexBefore(index, distance)).isEqualTo(expectedIndex);
  }

  @Test
  @DisplayName("Should list no segment start on the plain grid")
  void shouldListNoSegmentStartOnThePlainGrid() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(60), SIX_SECONDS);

    assertThat(timeline.segmentStartSlots()).isEmpty();
  }

  @Test
  @DisplayName("Should open no stream copy segment at a keyframe at or after the media end")
  void shouldOpenNoStreamCopySegmentAtAKeyframeAtOrAfterTheMediaEnd() {
    var timeline = streamCopyOver(Duration.parse("PT18.0004S"), 0, 6_000, 12_000, 18_000);

    assertThat(timeline.mediaSegmentCount()).isEqualTo(3);
    assertThat(timeline.mediaSegmentDuration(2)).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  @DisplayName(
      "Should keep each opening keyframe's index in the positions when a grid opens at keyframes")
  void shouldKeepEachOpeningKeyframesIndexInThePositionsWhenAGridOpensAtKeyframes() {
    var positions = millisecondPositions(0, 2_000, 6_000, 8_000, 12_000);
    var grid = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(12), SIX_SECONDS);

    var timeline = grid.openedAt(positions, new StreamCopySegments.Starts(List.of(0, 2, 4)));

    assertThat(timeline.openingKeyframes())
        .extracting(MediaSegmentTimeline.OpeningKeyframe::keyframeIndex)
        .containsExactly(0, 2);
  }

  @Test
  @DisplayName("Should advertise no stream copy segment when the first keyframe follows the end")
  void shouldAdvertiseNoStreamCopySegmentWhenTheFirstKeyframeFollowsTheEnd() {
    var timeline = streamCopyOver(Duration.ofMillis(400), 500);

    assertThat(timeline.mediaSegmentCount()).isZero();
  }

  @Test
  @DisplayName("Should open the first stream copy segment at zero when its keyframe presents later")
  void shouldOpenTheFirstStreamCopySegmentAtZeroWhenItsKeyframePresentsLater() {
    var timeline = streamCopyOver(Duration.ofSeconds(12), 500, 6_000);

    assertThat(durations(timeline)).containsExactly(Duration.ofSeconds(6), Duration.ofSeconds(6));
  }

  @Test
  @DisplayName("Should open a stream copy segment at its keyframe's time in the source time base")
  void shouldOpenAStreamCopySegmentAtItsKeyframesTimeInTheSourceTimeBase() {
    // In a 1001/24000 time base, 144 ticks are 6.006 s.
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(144, 144)))
            .build();
    var grid = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(12), SIX_SECONDS);

    var timeline = grid.openedAt(positions, everyKeyframeOf(positions));

    assertThat(durations(timeline))
        .containsExactly(Duration.ofMillis(6_006), Duration.ofMillis(5_994));
  }

  @Test
  @DisplayName("Should seek a later stream copy attempt to its keyframe's decode time, rounded up")
  void shouldSeekALaterStreamCopyAttemptToItsKeyframesDecodeTimeRoundedUp() {
    // In a 1001/24000 time base, 143 ticks decode at 5.96429166... s.
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1001)
            .timeBaseDenominator(24_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(List.of(new KeyframePosition(0, -2), new KeyframePosition(146, 143)))
            .build();
    var grid = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(12), SIX_SECONDS);

    var timeline = grid.openedAt(positions, everyKeyframeOf(positions));

    assertThat(timeline.keyframeSeekPosition(0)).isEmpty();
    assertThat(timeline.keyframeSeekPosition(1)).hasValue(Duration.ofNanos(5_964_291_667L));
  }

  @Test
  @DisplayName("Should seek a stream copy attempt no earlier than zero")
  void shouldSeekAStreamCopyAttemptNoEarlierThanZero() {
    var timeline =
        new MediaSegmentTimeline.StreamCopy(
            Duration.ofSeconds(12),
            SIX_SECONDS,
            List.of(
                new MediaSegmentTimeline.OpeningKeyframe(0, Duration.ZERO, Duration.ZERO),
                new MediaSegmentTimeline.OpeningKeyframe(
                    1, Duration.ofSeconds(6), Duration.ofMillis(-1))));

    assertThat(timeline.keyframeSeekPosition(1)).hasValue(Duration.ZERO);
  }

  @Test
  @DisplayName("Should seek no plain grid attempt to a keyframe")
  void shouldSeekNoPlainGridAttemptToAKeyframe() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(60), SIX_SECONDS);

    assertThat(timeline.keyframeSeekPosition(5)).isEmpty();
  }

  @Test
  @DisplayName("Should keep the opening keyframes it was given when the caller's list changes")
  void shouldKeepTheOpeningKeyframesItWasGivenWhenTheCallersListChanges() {
    var first = new MediaSegmentTimeline.OpeningKeyframe(0, Duration.ZERO, Duration.ZERO);
    var openingKeyframes = new ArrayList<>(List.of(first));
    var timeline =
        new MediaSegmentTimeline.StreamCopy(Duration.ofSeconds(6), SIX_SECONDS, openingKeyframes);

    openingKeyframes.add(
        new MediaSegmentTimeline.OpeningKeyframe(1, Duration.ofSeconds(3), Duration.ofSeconds(3)));

    assertThat(timeline.openingKeyframes()).containsExactly(first);
  }

  @Test
  @DisplayName("Should reject a stream copy timeline without a media duration")
  void shouldRejectAStreamCopyTimelineWithoutAMediaDuration() {
    var openingKeyframes = openingKeyframeAtZero();

    assertThatNullPointerException()
        .isThrownBy(() -> new MediaSegmentTimeline.StreamCopy(null, SIX_SECONDS, openingKeyframes));
  }

  @Test
  @DisplayName("Should reject a stream copy timeline without a target segment duration")
  void shouldRejectAStreamCopyTimelineWithoutATargetSegmentDuration() {
    var openingKeyframes = openingKeyframeAtZero();

    assertThatNullPointerException()
        .isThrownBy(() -> new MediaSegmentTimeline.StreamCopy(SIX_SECONDS, null, openingKeyframes));
  }

  @Test
  @DisplayName("Should reject an opening keyframe without a presentation time")
  void shouldRejectAnOpeningKeyframeWithoutAPresentationTime() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MediaSegmentTimeline.OpeningKeyframe(0, null, Duration.ZERO));
  }

  @Test
  @DisplayName("Should reject an opening keyframe without a decode time")
  void shouldRejectAnOpeningKeyframeWithoutADecodeTime() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MediaSegmentTimeline.OpeningKeyframe(0, Duration.ZERO, null));
  }

  @Test
  @DisplayName("Should reject a stream copy timeline without opening keyframes")
  void shouldRejectAStreamCopyTimelineWithoutOpeningKeyframes() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MediaSegmentTimeline.StreamCopy(SIX_SECONDS, SIX_SECONDS, null));
  }

  @Test
  @DisplayName("Should reject opening a grid without keyframe positions")
  void shouldRejectOpeningAGridWithoutKeyframePositions() {
    var grid = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(12), SIX_SECONDS);
    var starts = new StreamCopySegments.Starts(List.of(0));

    assertThatNullPointerException().isThrownBy(() -> grid.openedAt(null, starts));
  }

  @Test
  @DisplayName("Should reject opening a grid without segment starts")
  void shouldRejectOpeningAGridWithoutSegmentStarts() {
    var grid = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(12), SIX_SECONDS);
    var positions = millisecondPositions(0);

    assertThatNullPointerException().isThrownBy(() -> grid.openedAt(positions, null));
  }

  private static List<MediaSegmentTimeline.OpeningKeyframe> openingKeyframeAtZero() {
    return List.of(new MediaSegmentTimeline.OpeningKeyframe(0, Duration.ZERO, Duration.ZERO));
  }

  private static MediaSegmentTimeline.StreamCopy streamCopyOver(
      Duration mediaDuration, long... keyframeMillis) {
    var positions = millisecondPositions(keyframeMillis);
    return new MediaSegmentTimeline.PlainGrid(mediaDuration, SIX_SECONDS)
        .openedAt(positions, everyKeyframeOf(positions));
  }

  private static StreamCopySegments.Starts everyKeyframeOf(KeyframePositions positions) {
    return new StreamCopySegments.Starts(
        IntStream.range(0, positions.keyframes().size()).boxed().toList());
  }

  private static KeyframePositions millisecondPositions(long... keyframeMillis) {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator(1_000)
        .source(KeyframePositionSource.CONTAINER_INDEX)
        .keyframes(
            Arrays.stream(keyframeMillis)
                .mapToObj(time -> new KeyframePosition(time, time))
                .toList())
        .build();
  }

  private static List<Duration> durations(MediaSegmentTimeline timeline) {
    var durations = new ArrayList<Duration>();
    for (var index = 0; index < timeline.mediaSegmentCount(); index++) {
      durations.add(timeline.mediaSegmentDuration(index));
    }

    return durations;
  }
}
