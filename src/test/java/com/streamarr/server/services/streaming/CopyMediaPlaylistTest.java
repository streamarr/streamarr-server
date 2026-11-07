package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Copy Media Playlist Tests")
class CopyMediaPlaylistTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build();

  // Keyframes every 4 s open stream copy segments at 0, 8 and 12 s of a 14 s source.
  @Test
  @DisplayName(
      "Should list the stream copy's segments and their EXTINF total when keyframes open every"
          + " segment")
  void shouldListStreamCopysSegmentsAndExtinfTotalWhenKeyframesOpenEverySegment() {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .duration(Duration.ofSeconds(14))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(4), Duration.ofSeconds(14)))
            .build();

    var playlist = CopyMediaPlaylist.of(source, PROPERTIES);

    assertThat(playlist.segmentCount()).isEqualTo(3);
    assertThat(playlist.durationSeconds()).isEqualByComparingTo(new BigDecimal("14"));
  }

  // The 8 s first segment lifts the EXT-X-TARGETDURATION above the 6 s grid slot.
  @Test
  @DisplayName(
      "Should list each segment's opening keyframe, its exact start and the written target"
          + " duration when keyframes open every segment")
  void
      shouldListEachSegmentsOpeningKeyframeExactStartAndTargetDurationWhenKeyframesOpenEverySegment() {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .duration(Duration.ofSeconds(14))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(4), Duration.ofSeconds(14)))
            .build();

    var playlist = CopyMediaPlaylist.of(source, PROPERTIES);

    assertThat(playlist.segments())
        .containsExactly(
            CopyMediaPlaylist.Segment.builder()
                .openingKeyframeIndex(0)
                .startTicks(0)
                .extinfSeconds(new BigDecimal("8.000000"))
                .build(),
            CopyMediaPlaylist.Segment.builder()
                .openingKeyframeIndex(2)
                .startTicks(8_000)
                .extinfSeconds(new BigDecimal("4.000000"))
                .build(),
            CopyMediaPlaylist.Segment.builder()
                .openingKeyframeIndex(3)
                .startTicks(12_000)
                .extinfSeconds(new BigDecimal("2.000000"))
                .build());
    assertThat(playlist.startTimeBase()).isEqualTo(new Fraction(1, 1_000));
    assertThat(playlist.targetDurationSeconds()).isEqualTo(8);
  }

  @Test
  @DisplayName("Should list no media playlist when the source's keyframes are not known")
  void shouldListNoMediaPlaylistWhenSourcesKeyframesAreNotKnown() {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .keyframes(new KeyframeKnowledge.NotReported())
            .build();

    assertThat(CopyMediaPlaylist.of(source, PROPERTIES)).isEqualTo(CopyMediaPlaylist.UNKNOWN);
  }

  // Keyframes on a 1/10,000,000 time base at 6.0000004 s and 12.0000008 s of an 18 s source open
  // segments that the media playlist writes as 6.000000, 6.000000 and 5.999999 s (RFC 8216
  // §4.3.2.1 EXTINF at microsecond precision).
  @Test
  @DisplayName(
      "Should total the EXTINF durations as the media playlist writes them when they sum to less"
          + " than the source's duration")
  void shouldTotalExtinfDurationsAsMediaPlaylistWritesThemWhenTheySumToLessThanSourcesDuration() {
    var positions =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(10_000_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(
                List.of(
                    new KeyframePosition(0, 0),
                    new KeyframePosition(60_000_004L, 60_000_004L),
                    new KeyframePosition(120_000_008L, 120_000_008L)))
            .build();
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .duration(Duration.ofSeconds(18))
            .keyframes(new KeyframeKnowledge.Known(positions))
            .build();

    var playlist = CopyMediaPlaylist.of(source, PROPERTIES);

    assertThat(playlist.segmentCount()).isEqualTo(3);
    assertThat(playlist.durationSeconds()).isEqualByComparingTo(new BigDecimal("17.999999"));
  }

  // At the 6 s period, keyframes 10 s apart leave the slots [12 s, 18 s) and [24 s, 30 s) without
  // one, so each joins the segment before it, and every segment starts at its keyframe.
  @Test
  @DisplayName(
      "Should list each merged segment from its keyframe's exact start when a slot holds no"
          + " keyframe")
  void shouldListEachMergedSegmentFromItsKeyframesExactStartWhenASlotHoldsNoKeyframe() {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .duration(Duration.ofSeconds(36))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(10), Duration.ofSeconds(36)))
            .build();

    var playlist = CopyMediaPlaylist.of(source, PROPERTIES);

    assertThat(playlist.segments())
        .extracting(CopyMediaPlaylist.Segment::startTicks)
        .containsExactly(0L, 10_000L, 20_000L, 30_000L);
    assertThat(playlist.segments())
        .extracting(segment -> segment.extinfSeconds().toPlainString())
        .containsExactly("10.000000", "10.000000", "10.000000", "6.000000");
    assertThat(playlist.targetDurationSeconds()).isEqualTo(10);
  }

  @Test
  @DisplayName(
      "Should refuse to list a media playlist when the source, the properties or the timeline is"
          + " missing")
  void shouldRefuseToListMediaPlaylistWhenSourcePropertiesOrTimelineIsMissing() {
    var source = StreamSessionFixture.defaultProbeBuilder().build();
    var positions = ((KeyframeKnowledge.Known) source.keyframes()).positions();
    var timeline = MediaSegmentTimelines.streamCopyOf(source, PROPERTIES).orElseThrow();

    assertThatNullPointerException()
        .isThrownBy(() -> CopyMediaPlaylist.of(null, PROPERTIES))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> CopyMediaPlaylist.of(source, null))
        .withMessageContaining("properties");
    assertThatNullPointerException()
        .isThrownBy(() -> CopyMediaPlaylist.of((MediaSegmentTimeline.StreamCopy) null, positions))
        .withMessageContaining("timeline");
    assertThatNullPointerException()
        .isThrownBy(() -> CopyMediaPlaylist.of(timeline, null))
        .withMessageContaining("positions");
  }

  @Test
  @DisplayName(
      "Should refuse a media playlist without segments or a start time base, or a segment without a"
          + " duration")
  void shouldRefuseMediaPlaylistWithoutSegmentsOrStartTimeBaseOrSegmentWithoutDuration() {
    assertThatNullPointerException()
        .isThrownBy(() -> new CopyMediaPlaylist(null, 6, new Fraction(1, 1)))
        .withMessageContaining("segments");
    assertThatNullPointerException()
        .isThrownBy(() -> new CopyMediaPlaylist(List.of(), 6, null))
        .withMessageContaining("startTimeBase");
    assertThatNullPointerException()
        .isThrownBy(() -> CopyMediaPlaylist.Segment.builder().build())
        .withMessageContaining("extinfSeconds");
  }
}
