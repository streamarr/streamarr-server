package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Short Copy Tests")
class ShortCopyTest {

  private static final Duration TARGET = Duration.ofSeconds(6);
  private static final MediaSegmentTimeline GRID =
      new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(60), TARGET);

  @Test
  @DisplayName(
      "Should end normally when every source stream ends within one target segment duration of the copy's end")
  void shouldEndNormallyWhenEverySourceStreamEndsWithinOneTargetSegmentDurationOfTheCopysEnd() {
    var probe = probeWithStreamsEndingAt(Duration.ofSeconds(20), Duration.ofSeconds(30));

    var shortCopy = ShortCopy.endingWith(3, GRID, probe);

    assertThat(shortCopy).isEqualTo(new ShortCopy(3, Duration.ofSeconds(24), true));
  }

  @Test
  @DisplayName(
      "Should leave media after the copy when a source stream ends more than one target segment duration after the copy's end")
  void shouldLeaveMediaAfterTheCopyWhenASourceStreamEndsMoreThanOneTargetSegmentDurationLater() {
    var probe = probeWithStreamsEndingAt(Duration.ofSeconds(24), Duration.ofMillis(30_001));

    var shortCopy = ShortCopy.endingWith(3, GRID, probe);

    assertThat(shortCopy.everySourceStreamEnded()).isFalse();
  }

  // Nothing shows that a stream whose header states no duration has media past the copy's end.
  @Test
  @DisplayName("Should count a source stream without a probed end time as ended")
  void shouldCountASourceStreamWithoutAProbedEndTimeAsEnded() {
    var probe =
        defaultProbeBuilder()
            .streams(List.of(audioStreamBuilder("aac").endTime(Optional.empty()).build()))
            .build();

    var shortCopy = ShortCopy.endingWith(3, GRID, probe);

    assertThat(shortCopy.everySourceStreamEnded()).isTrue();
  }

  @Test
  @DisplayName("Should end the copy where the keyframe that opens the next segment presents")
  void shouldEndTheCopyWhereTheKeyframeThatOpensTheNextSegmentPresents() {
    var streamCopy =
        new MediaSegmentTimeline.StreamCopy(
            Duration.ofSeconds(24),
            TARGET,
            List.of(
                openingAt(Duration.ZERO),
                openingAt(Duration.ofMillis(6_500)),
                openingAt(Duration.ofMillis(12_250)),
                openingAt(Duration.ofSeconds(18))));

    var shortCopy = ShortCopy.endingWith(1, streamCopy, probeWithStreamsEndingAt());

    assertThat(shortCopy.mediaEnd()).isEqualTo(Duration.ofMillis(12_250));
  }

  private static MediaProbe probeWithStreamsEndingAt(Duration... ends) {
    var streams =
        List.of(ends).stream()
            .map(end -> audioStreamBuilder("aac").endTime(Optional.of(end)).build())
            .toList();
    return defaultProbeBuilder().streams(streams).build();
  }

  private static MediaSegmentTimeline.OpeningKeyframe openingAt(Duration presentationTime) {
    return new MediaSegmentTimeline.OpeningKeyframe(0, presentationTime, presentationTime);
  }
}
