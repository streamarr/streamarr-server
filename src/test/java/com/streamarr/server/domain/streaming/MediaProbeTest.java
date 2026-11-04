package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Media Probe Tests")
class MediaProbeTest {

  @ParameterizedTest
  @ValueSource(strings = {"hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle"})
  @DisplayName(
      "Should find the forced image subtitle when its language matches the first audio track")
  void shouldFindTheForcedImageSubtitleWhenItsLanguageMatchesTheFirstAudioTrack(String codec) {
    var forced = subtitle(codec, "eng").isForced(true).index(3).build();
    var probe = probe(audio("eng"), audio("fra"), subtitle(codec, "eng").index(2).build(), forced);

    assertThat(probe.forcedImageSubtitleOfSelectedAudio()).contains(forced);
  }

  @Test
  @DisplayName("Should match the languages without regard to case")
  void shouldMatchTheLanguagesWithoutRegardToCase() {
    var forced = subtitle("hdmv_pgs_subtitle", "ENG").isForced(true).build();

    assertThat(probe(audio("eng"), forced).forcedImageSubtitleOfSelectedAudio()).contains(forced);
  }

  @Test
  @DisplayName("Should find no forced subtitle when the forced image track is in another language")
  void shouldFindNoForcedSubtitleWhenTheForcedImageTrackIsInAnotherLanguage() {
    var probe =
        probe(
            audio("eng"),
            audio("fra"),
            subtitle("hdmv_pgs_subtitle", "fra").isForced(true).build());

    assertThat(probe.forcedImageSubtitleOfSelectedAudio()).isEmpty();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the forced track in the language is text")
  void shouldFindNoForcedSubtitleWhenTheForcedTrackInTheLanguageIsText() {
    var probe = probe(audio("eng"), subtitle("subrip", "eng").isForced(true).build());

    assertThat(probe.forcedImageSubtitleOfSelectedAudio()).isEmpty();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the first audio track names no language")
  void shouldFindNoForcedSubtitleWhenTheFirstAudioTrackNamesNoLanguage() {
    var probe =
        probe(
            audioStreamBuilder("aac").build(),
            subtitle("hdmv_pgs_subtitle", "eng").isForced(true).build());

    assertThat(probe.forcedImageSubtitleOfSelectedAudio()).isEmpty();
  }

  private static MediaProbe probe(StreamInfo... streams) {
    return StreamSessionFixture.defaultProbeBuilder().streams(List.of(streams)).build();
  }

  private static StreamInfo audio(String language) {
    return audioStreamBuilder("aac").language(Optional.of(language)).build();
  }

  private static StreamInfo.StreamInfoBuilder subtitle(String codec, String language) {
    return StreamInfo.builder()
        .codecType("subtitle")
        .codec(Optional.of(codec))
        .language(Optional.of(language));
  }
}
