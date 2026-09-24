package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
    var timeline = new MediaSegmentTimeline(mediaDuration, SIX_SECONDS);

    assertThat(timeline.mediaSegmentCount()).isEqualTo(expectedCount);
  }

  @Test
  @DisplayName("Should end the last media segment at the media duration when it runs short")
  void shouldEndTheLastMediaSegmentAtTheMediaDurationWhenItRunsShort() {
    var timeline = new MediaSegmentTimeline(Duration.parse("PT2M5.5S"), SIX_SECONDS);

    assertThat(timeline.mediaSegmentDuration(0)).isEqualTo(SIX_SECONDS);
    assertThat(timeline.mediaSegmentDuration(19)).isEqualTo(SIX_SECONDS);
    assertThat(timeline.mediaSegmentDuration(20)).isEqualTo(Duration.ofMillis(5_500));
  }

  @Test
  @DisplayName("Should start each media segment on the zero-based grid when locating it")
  void shouldStartEachMediaSegmentOnTheZeroBasedGridWhenLocatingIt() {
    var timeline = new MediaSegmentTimeline(Duration.ofHours(2), SIX_SECONDS);

    assertThat(timeline.mediaSegmentStartSeconds(0)).isZero();
    assertThat(timeline.mediaSegmentStartSeconds(900)).isEqualTo(5_400);
  }

  @Test
  @DisplayName("Should keep whole seconds of the target when it carries a fraction")
  void shouldKeepWholeSecondsOfTheTargetWhenItCarriesAFraction() {
    var timeline = new MediaSegmentTimeline(Duration.ofSeconds(13), Duration.ofMillis(6_500));

    assertThat(timeline.targetSegmentDurationSeconds()).isEqualTo(6);
    assertThat(timeline.mediaSegmentCount()).isEqualTo(3);
    assertThat(timeline.mediaSegmentDuration(2)).isEqualTo(Duration.ofSeconds(1));
  }
}
