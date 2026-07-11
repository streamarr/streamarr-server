package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

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

  @ParameterizedTest(name = "channels={0} → bitrate={1}")
  @DisplayName("Should calculate bitrate when channel count given")
  @CsvSource({"1, 64000", "2, 128000", "6, 384000", "8, 512000"})
  void shouldCalculateBitrateWhenChannelCountGiven(int channels, long expectedBitrate) {
    assertThat(AudioDecision.bitrateForChannels(channels)).isEqualTo(expectedBitrate);
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

  @ParameterizedTest(name = "codec={0} → hls={1}")
  @DisplayName("Should return correct HLS codec string when codec varies")
  @CsvSource({"aac, mp4a.40.2", "ac3, ac-3", "eac3, ec-3"})
  void shouldReturnCorrectHlsCodecStringWhenCodecVaries(String codec, String expected) {
    var decision = new AudioDecision(AudioMode.COPY, codec, 2, 128_000L);
    assertThat(decision.hlsCodecString()).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should return empty HLS codec string when audio mode is none")
  void shouldReturnEmptyHlsCodecStringWhenAudioModeIsNone() {
    assertThat(AudioDecision.none().hlsCodecString()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve source values when copy decision created")
  void shouldPreserveSourceValuesWhenCopyDecisionCreated() {
    var decision = AudioDecision.copy("ac3", 6, 384_000L);

    assertThat(decision.mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.codec()).isEqualTo("ac3");
    assertThat(decision.channels()).isEqualTo(6);
    assertThat(decision.bitrate()).isEqualTo(384_000L);
  }

  @Test
  @DisplayName("Should return AAC codec string when codec is unrecognized")
  void shouldReturnAacCodecStringWhenCodecIsUnrecognized() {
    var decision = new AudioDecision(AudioMode.COPY, "opus", 2, 128_000L);

    assertThat(decision.hlsCodecString()).isEqualTo("mp4a.40.2");
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
