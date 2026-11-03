package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Audio Decision Tests")
class AudioDecisionTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("announcedBitrates")
  @DisplayName("Should calculate bitrate when codec and channel count given")
  void shouldCalculateBitrateWhenCodecAndChannelCountGiven(BitrateCase bitrate) {
    assertThat(AudioDecision.bitrateFor(bitrate.codec(), bitrate.channels()))
        .isEqualTo(bitrate.expected());
  }

  // AC-3 takes the 640 kb/s that FFmpeg's encoder stops at, and E-AC-3 the 768 kb/s that streamed
  // 5.1 E-AC-3 commonly uses. A 5.0 encode announces its codec's 5.1 bitrate, and a 7.1 track
  // never announces less.
  static Stream<BitrateCase> announcedBitrates() {
    return Stream.of(
        BitrateCase.builder().codec("aac").channels(1).expected(64_000L).build(),
        BitrateCase.builder().codec("aac").channels(2).expected(128_000L).build(),
        BitrateCase.builder().codec("aac").channels(5).expected(384_000L).build(),
        BitrateCase.builder().codec("aac").channels(6).expected(384_000L).build(),
        BitrateCase.builder().codec("aac").channels(7).expected(512_000L).build(),
        BitrateCase.builder().codec("aac").channels(8).expected(512_000L).build(),
        BitrateCase.builder().codec("aac").channels(10).expected(512_000L).build(),
        BitrateCase.builder().codec("ac3").channels(6).expected(640_000L).build(),
        BitrateCase.builder().codec("eac3").channels(6).expected(768_000L).build(),
        BitrateCase.builder().codec("eac3").channels(8).expected(768_000L).build());
  }

  @Builder
  record BitrateCase(String codec, int channels, long expected) {}

  @Test
  @DisplayName("Should reject a bitrate lookup when the codec is missing")
  void shouldRejectABitrateLookupWhenTheCodecIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> AudioDecision.bitrateFor(null, 6))
        .withMessageContaining("codec");
  }

  @Test
  @DisplayName("Should set stereo AAC defaults when factory method called")
  void shouldSetStereoAacDefaultsWhenFactoryMethodCalled() {
    var decision = AudioDecision.stereoAac();

    assertThat(decision.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.codec()).isEqualTo("aac");
    assertThat(decision.channels()).isEqualTo(2);
    assertThat(decision.channelLayout()).contains(ChannelLayout.STEREO);
    assertThat(decision.bitrate()).isEqualTo(128_000L);
  }

  // ADR 0050: every audio encode names its output channel layout.
  @Test
  @DisplayName("Should refuse an encode when it names no output layout")
  void shouldRefuseAnEncodeWhenItNamesNoOutputLayout() {
    var decision =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("aac")
            .channels(2)
            .bitrate(128_000L);

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("layout");
  }

  @Test
  @DisplayName("Should refuse an encode when its layout holds another channel count")
  void shouldRefuseAnEncodeWhenItsLayoutHoldsAnotherChannelCount() {
    var decision =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("aac")
            .channels(6)
            .bitrate(384_000L)
            .channelLayout(Optional.of(ChannelLayout.FIVE_POINT_ZERO));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("layout");
  }

  // A copy keeps the source's own layout, which the copy job does not name.
  @Test
  @DisplayName("Should refuse an output layout when the decision copies the audio")
  void shouldRefuseAnOutputLayoutWhenTheDecisionCopiesTheAudio() {
    var decision =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("aac")
            .channels(2)
            .bitrate(128_000L)
            .sourceCodecString(Optional.of("mp4a.40.2"))
            .channelLayout(Optional.of(ChannelLayout.STEREO));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("layout");
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
            .channelLayout(Optional.of(ChannelLayout.FIVE_POINT_ONE))
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
