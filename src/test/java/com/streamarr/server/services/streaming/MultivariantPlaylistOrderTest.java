package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Multivariant Playlist Order Tests")
class MultivariantPlaylistOrderTest {

  private static final long STEREO_AAC_BITRATE = 128_000L;

  @Test
  @DisplayName(
      "Should list 480p first and the others from highest to lowest when the ladder is the default ladder")
  void shouldList480pFirstAndTheOthersFromHighestToLowestWhenTheLadderIsTheDefaultLadder() {
    var ladder =
        List.of(
            variant("1080p", 5_000_000L),
            variant("720p", 3_000_000L),
            variant("480p", 1_500_000L),
            variant("360p", 800_000L));

    var ordered = MultivariantPlaylistOrder.of(ladder, STEREO_AAC_BITRATE);

    assertThat(ordered)
        .extracting(QualityVariant::label)
        .containsExactly("480p", "1080p", "720p", "360p");
  }

  @Test
  @DisplayName(
      "Should list the variant nearest 2000 kb/s first when the ladder has no 480p variant")
  void shouldListTheVariantNearest2000KbpsFirstWhenTheLadderHasNo480pVariant() {
    var ladder =
        List.of(
            variant("1080p", 5_000_000L), variant("720p", 3_000_000L), variant("360p", 800_000L));

    var ordered = MultivariantPlaylistOrder.of(ladder, STEREO_AAC_BITRATE);

    assertThat(ordered).extracting(QualityVariant::label).containsExactly("360p", "1080p", "720p");
  }

  @Test
  @DisplayName(
      "Should measure each variant's distance from 2000 kb/s with the playlist's audio bitrate included")
  void shouldMeasureEachVariantsDistanceFrom2000KbpsWithThePlaylistsAudioBitrateIncluded() {
    var ladder = List.of(variant("high", 2_500_000L), variant("low", 1_400_000L));

    assertThat(MultivariantPlaylistOrder.of(ladder, 0L))
        .extracting(QualityVariant::label)
        .containsExactly("high", "low");
    assertThat(MultivariantPlaylistOrder.of(ladder, STEREO_AAC_BITRATE))
        .extracting(QualityVariant::label)
        .containsExactly("low", "high");
  }

  @Test
  @DisplayName("Should list the variants from highest to lowest when the given ladder is unordered")
  void shouldListTheVariantsFromHighestToLowestWhenTheGivenLadderIsUnordered() {
    var ladder =
        List.of(
            variant("360p", 800_000L),
            variant("1080p", 5_000_000L),
            variant("480p", 1_500_000L),
            variant("720p", 3_000_000L));

    var ordered = MultivariantPlaylistOrder.of(ladder, STEREO_AAC_BITRATE);

    assertThat(ordered)
        .extracting(QualityVariant::label)
        .containsExactly("480p", "1080p", "720p", "360p");
  }

  @Test
  @DisplayName("Should list nothing when the ladder is empty")
  void shouldListNothingWhenTheLadderIsEmpty() {
    assertThat(MultivariantPlaylistOrder.of(List.of(), STEREO_AAC_BITRATE)).isEmpty();
  }

  private static QualityVariant variant(String label, long videoBitrate) {
    return StreamSessionFixture.defaultVariantBuilder()
        .label(label)
        .videoBitrate(videoBitrate)
        .build();
  }
}
