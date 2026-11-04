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
      "Should find a forced image subtitle when its language matches the first audio track")
  void shouldFindAForcedImageSubtitleWhenItsLanguageMatchesTheFirstAudioTrack(String codec) {
    var probe = probe(audio("eng"), audio("fra"), subtitle(codec, "eng").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should find a forced image subtitle when its language matches a later audio track")
  void shouldFindAForcedImageSubtitleWhenItsLanguageMatchesALaterAudioTrack() {
    var probe =
        probe(
            audio("eng"),
            audio("fra"),
            subtitle("hdmv_pgs_subtitle", "fra").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should match the languages without regard to case")
  void shouldMatchTheLanguagesWithoutRegardToCase() {
    var probe = probe(audio("eng"), subtitle("hdmv_pgs_subtitle", "ENG").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the forced image track matches no audio track")
  void shouldFindNoForcedSubtitleWhenTheForcedImageTrackMatchesNoAudioTrack() {
    var probe =
        probe(
            audio("eng"),
            audio("fra"),
            subtitle("hdmv_pgs_subtitle", "deu").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the image track in the language is not forced")
  void shouldFindNoForcedSubtitleWhenTheImageTrackInTheLanguageIsNotForced() {
    var probe = probe(audio("eng"), subtitle("hdmv_pgs_subtitle", "eng").build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the forced track in the language is text")
  void shouldFindNoForcedSubtitleWhenTheForcedTrackInTheLanguageIsText() {
    var probe = probe(audio("eng"), subtitle("subrip", "eng").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when no audio track names a language")
  void shouldFindNoForcedSubtitleWhenNoAudioTrackNamesALanguage() {
    var probe =
        probe(
            audioStreamBuilder("aac").build(),
            subtitle("hdmv_pgs_subtitle", "eng").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
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
