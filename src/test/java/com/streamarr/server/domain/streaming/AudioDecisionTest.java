package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Audio Decision Tests")
class AudioDecisionTest {

  @ParameterizedTest(name = "sourceChannels={0} → normalized={1}")
  @DisplayName("Should normalize channel count when source channels vary")
  @CsvSource({"0, 2", "1, 1", "2, 2", "3, 2", "4, 2", "5, 6", "6, 6", "7, 8", "8, 8"})
  void shouldNormalizeChannelCountWhenSourceChannelsVary(int sourceChannels, int expected) {
    assertThat(AudioDecision.normalizeChannels(sourceChannels)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} at channels={1} → bitrate={2}")
  @DisplayName("Should calculate bitrate when codec and channel count given")
  @CsvSource({
    "aac, 1, 64000",
    "aac, 2, 128000",
    "aac, 6, 384000",
    "aac, 8, 512000",
    "ac3, 6, 640000",
    "eac3, 6, 768000"
  })
  void shouldCalculateBitrateWhenCodecAndChannelCountGiven(
      String codec, int channels, long expectedBitrate) {
    assertThat(AudioDecision.bitrateFor(codec, channels)).isEqualTo(expectedBitrate);
  }

  @Test
  @DisplayName("Should set stereo AAC defaults when factory method called")
  void shouldSetStereoAacDefaultsWhenFactoryMethodCalled() {
    var decision = AudioDecision.stereoAac();

    assertThat(decision.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.codec()).isEqualTo("aac");
    assertThat(decision.channels()).isEqualTo(2);
    assertThat(decision.bitrate()).isEqualTo(128_000L);
  }

  @Test
  @DisplayName("Should return the codec's HLS codec string when HLS delivery carries the codec")
  void shouldReturnCodecsHlsCodecStringWhenHlsDeliveryCarriesTheCodec() {
    assertThat(
            AudioDecision.builder()
                .mode(AudioMode.COPY)
                .codec("eac3")
                .channels(6)
                .bitrate(640_000L)
                .sourceCodecString(Optional.of("ec-3"))
                .build()
                .hlsCodecString())
        .isEqualTo("ec-3");
  }

  @Test
  @DisplayName("Should return empty HLS codec string when audio mode is none")
  void shouldReturnEmptyHlsCodecStringWhenAudioModeIsNone() {
    assertThat(AudioDecision.none().hlsCodecString()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse an audio copy when the source codec string is absent")
  void shouldRefuseAnAudioCopyWhenTheSourceCodecStringIsAbsent() {
    var decision =
        AudioDecision.builder().mode(AudioMode.COPY).codec("aac").channels(2).bitrate(128_000L);

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codec string");
  }

  @Test
  @DisplayName("Should preserve source values when copy decision created")
  void shouldPreserveSourceValuesWhenCopyDecisionCreated() {
    var decision =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("ac3")
            .channels(6)
            .bitrate(384_000L)
            .sourceCodecString(Optional.of("ac-3"))
            .build();

    assertThat(decision.mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.codec()).isEqualTo("ac3");
    assertThat(decision.channels()).isEqualTo(6);
    assertThat(decision.bitrate()).isEqualTo(384_000L);
  }

  @Test
  @DisplayName("Should refuse an HLS codec string when HLS delivery cannot carry the codec")
  void shouldRefuseHlsCodecStringWhenHlsDeliveryCannotCarryTheCodec() {
    var decision =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("dts")
            .channels(6)
            .bitrate(768_000L)
            .build();

    assertThatThrownBy(decision::hlsCodecString)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dts");
  }

  @Test
  @DisplayName("Should set null codec and zero values when none decision created")
  void shouldSetNullCodecAndZeroValuesWhenNoneDecisionCreated() {
    var decision = AudioDecision.none();

    assertThat(decision.mode()).isEqualTo(AudioMode.NONE);
    assertThat(decision.codec()).isNull();
    assertThat(decision.channels()).isZero();
    assertThat(decision.bitrate()).isZero();
  }
}
