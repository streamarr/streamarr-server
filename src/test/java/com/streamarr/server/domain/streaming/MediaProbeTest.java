package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  // ISO 639-2 gives 20 languages a bibliographic and a terminological code, and files carry either,
  // or the ISO 639-1 code.
  @ParameterizedTest(name = "audio {0}, subtitle {1}")
  @CsvSource({"fra, fre", "fre, fra", "deu, ger", "zho, chi", "en, eng", "eng, en", "fr, fre"})
  @DisplayName(
      "Should find a forced image subtitle when its language code is an alias of the audio's")
  void shouldFindAForcedImageSubtitleWhenItsLanguageCodeIsAnAliasOfTheAudios(
      String audioLanguage, String subtitleLanguage) {
    var probe =
        probe(
            audio("eng"),
            audio(audioLanguage),
            subtitle("hdmv_pgs_subtitle", subtitleLanguage).isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should find no forced subtitle when two unknown two-letter codes differ")
  void shouldFindNoForcedSubtitleWhenTwoUnknownTwoLetterCodesDiffer() {
    var probe = probe(audio("qq"), subtitle("hdmv_pgs_subtitle", "qz").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
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

  // The session plays the first audio stream that the probe lists, and every audio decision reads
  // the probe's audio facts.
  @Test
  @DisplayName(
      "Should list the named audio track first and take the audio facts from it when a session"
          + " names a later track")
  void shouldListTheNamedAudioTrackFirstAndTakeTheAudioFactsFromItWhenASessionNamesALaterTrack() {
    var video = StreamInfo.builder().index(0).codecType("video").build();
    var english = audioIn("eng").index(1).build();
    var french =
        audioStreamBuilder("ac3")
            .index(2)
            .language(Optional.of("fra"))
            .channels(OptionalInt.of(6))
            .bitrate(OptionalLong.of(448_000))
            .audioProperties(
                Optional.of(
                    AudioStreamProperties.builder()
                        .channelLayout(Optional.of("5.1(side)"))
                        .build()))
            .build();
    var subtitle = subtitle("subrip", "eng").index(3).build();

    var named = probe(video, english, french, subtitle).withSessionAudioTrackAt(1);

    assertThat(named.streams()).containsExactly(video, french, english, subtitle);
    assertThat(named.audioCodec()).isEqualTo("ac3");
    assertThat(named.audioChannels()).hasValue(6);
    assertThat(named.audioBitrate()).hasValue(448_000);
    assertThat(named.audioChannelLayout()).contains("5.1(side)");
    assertThat(named.sessionAudioTrackPosition()).hasValue(1);
  }

  @Test
  @DisplayName("Should keep the probe when a session names its first audio track")
  void shouldKeepTheProbeWhenASessionNamesItsFirstAudioTrack() {
    var probe = probe(audioIn("eng").index(1).build(), audioIn("fra").index(2).build());

    assertThat(probe.withSessionAudioTrackAt(0)).isEqualTo(probe);
  }

  private static MediaProbe probe(StreamInfo... streams) {
    return StreamSessionFixture.defaultProbeBuilder().streams(List.of(streams)).build();
  }

  private static StreamInfo audio(String language) {
    return audioIn(language).build();
  }

  private static StreamInfo.StreamInfoBuilder audioIn(String language) {
    return audioStreamBuilder("aac").language(Optional.of(language));
  }

  private static StreamInfo.StreamInfoBuilder subtitle(String codec, String language) {
    return StreamInfo.builder()
        .codecType("subtitle")
        .codec(Optional.of(codec))
        .language(Optional.of(language));
  }
}
