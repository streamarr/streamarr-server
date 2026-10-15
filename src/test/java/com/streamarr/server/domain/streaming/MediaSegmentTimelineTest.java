package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
      "Should count media segments over the whole milliseconds of the media when sizing the timeline")
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

  @Test
  @DisplayName("Should start each media segment on the zero-based grid when locating it")
  void shouldStartEachMediaSegmentOnTheZeroBasedGridWhenLocatingIt() {
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofHours(2), SIX_SECONDS);

    assertThat(timeline.mediaSegmentStartSeconds(0)).isZero();
    assertThat(timeline.mediaSegmentStartSeconds(900)).isEqualTo(5_400);
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

  @Test
  @DisplayName("Should open no stream copy segment at a keyframe at or after the media end")
  void shouldOpenNoStreamCopySegmentAtAKeyframeAtOrAfterTheMediaEnd() {
    var timeline = streamCopyOver(Duration.parse("PT18.0004S"), 0, 6_000, 12_000, 18_000);

    assertThat(timeline.mediaSegmentCount()).isEqualTo(3);
    assertThat(timeline.mediaSegmentDuration(2)).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  @DisplayName("Should advertise no stream copy segment when the first keyframe follows the end")
  void shouldAdvertiseNoStreamCopySegmentWhenTheFirstKeyframeFollowsTheEnd() {
    var timeline = streamCopyOver(Duration.ofMillis(400), 500);

    assertThat(timeline.mediaSegmentCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should start the first stream copy segment at zero when its keyframe presents later")
  void shouldStartTheFirstStreamCopySegmentAtZeroWhenItsKeyframePresentsLater() {
    var timeline = streamCopyOver(Duration.ofSeconds(12), 500, 6_000);

    assertThat(durations(timeline)).containsExactly(Duration.ofSeconds(6), Duration.ofSeconds(6));
  }

  @Test
  @DisplayName("Should start a stream copy segment at its keyframe's time in the source time base")
  void shouldStartAStreamCopySegmentAtItsKeyframesTimeInTheSourceTimeBase() {
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

    var timeline = grid.openedAt(positions, new StreamCopySegments.Starts(positions.keyframes()));

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

    var timeline = grid.openedAt(positions, new StreamCopySegments.Starts(positions.keyframes()));

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
                new MediaSegmentTimeline.OpeningKeyframe(Duration.ZERO, Duration.ZERO),
                new MediaSegmentTimeline.OpeningKeyframe(
                    Duration.ofSeconds(6), Duration.ofMillis(-1))));

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
    var first = new MediaSegmentTimeline.OpeningKeyframe(Duration.ZERO, Duration.ZERO);
    var openingKeyframes = new ArrayList<>(List.of(first));
    var timeline =
        new MediaSegmentTimeline.StreamCopy(Duration.ofSeconds(6), SIX_SECONDS, openingKeyframes);

    openingKeyframes.add(
        new MediaSegmentTimeline.OpeningKeyframe(Duration.ofSeconds(3), Duration.ofSeconds(3)));

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
        .isThrownBy(() -> new MediaSegmentTimeline.OpeningKeyframe(null, Duration.ZERO));
  }

  @Test
  @DisplayName("Should reject an opening keyframe without a decode time")
  void shouldRejectAnOpeningKeyframeWithoutADecodeTime() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MediaSegmentTimeline.OpeningKeyframe(Duration.ZERO, null));
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
    var starts = new StreamCopySegments.Starts(List.of(new KeyframePosition(0, 0)));

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
    return List.of(new MediaSegmentTimeline.OpeningKeyframe(Duration.ZERO, Duration.ZERO));
  }

  private static MediaSegmentTimeline.StreamCopy streamCopyOver(
      Duration mediaDuration, long... keyframeMillis) {
    var positions = millisecondPositions(keyframeMillis);
    return new MediaSegmentTimeline.PlainGrid(mediaDuration, SIX_SECONDS)
        .openedAt(positions, new StreamCopySegments.Starts(positions.keyframes()));
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
