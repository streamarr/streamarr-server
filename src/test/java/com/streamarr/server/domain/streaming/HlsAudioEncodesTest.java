package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("HLS Audio Encodes Tests")
class HlsAudioEncodesTest {

  // Two AC-3 entries accept up to 2 and up to 6 channels, so AC-3 accepts up to 6. An entry that
  // does not apply to HLS counts for nothing.
  @Test
  @DisplayName(
      "Should take the largest maximum of a codec's entries for HLS when several entries name it")
  void shouldTakeTheLargestMaximumOfACodecsEntriesForHlsWhenSeveralEntriesNameIt() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(2).build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).hls(false).build()))
            .build();

    assertThat(HlsAudioEncodes.channelMaximum(declaration, "ac3")).hasValue(6);
    assertThat(HlsAudioEncodes.channelMaximum(declaration, "eac3")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"8, eac3, 6, 768000", "6, eac3, 6, 768000", "2, aac, 2, 128000", "1, aac, 1, 64000"})
  @DisplayName(
      "Should encode E-AC-3 5.1 at most and AAC with at most two channels otherwise when the player"
          + " declares 7.1 E-AC-3")
  void shouldEncodeEac351AtMostAndAacWithAtMostTwoChannelsOtherwiseWhenThePlayerDeclares71Eac3(
      int normalizedChannels, String codec, int channels, long bitrate) {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).build()))
            .build();

    assertThat(HlsAudioEncodes.forTrack(declaration, normalizedChannels))
        .extracting(AudioDecision::codec, AudioDecision::channels, AudioDecision::bitrate)
        .containsExactly(codec, channels, bitrate);
  }

  @Test
  @DisplayName("Should refuse to choose an encode when the declaration or the codec is missing")
  void shouldRefuseToChooseAnEncodeWhenTheDeclarationOrTheCodecIsMissing() {
    var declaration = defaultDeclarationBuilder().build();

    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.channelMaximum(null, "aac"))
        .withMessageContaining("declaration");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.channelMaximum(declaration, null))
        .withMessageContaining("codec");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.forTrack(null, 2))
        .withMessageContaining("declaration");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.aacForTrack(null, 2))
        .withMessageContaining("declaration");
  }
}
