package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Tests")
class TranscodeDecisionTest {

  private static final VideoPlaybackFacts FACTS_WITHOUT_CODEC_STRING =
      VideoPlaybackFactsFixture.sdrH264FactsBuilder().codecString(Optional.empty()).build();

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should refuse a decision that copies the video when the probe stored no codec string")
  void shouldRefuseADecisionThatCopiesTheVideoWhenTheProbeStoredNoCodecString(TranscodeMode mode) {
    var decision = decisionBuilder(mode).videoSource(Optional.of(FACTS_WITHOUT_CODEC_STRING));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codec string");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should accept a decision that encodes the video when the probe stored no codec string")
  void shouldAcceptADecisionThatEncodesTheVideoWhenTheProbeStoredNoCodecString(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode).videoSource(Optional.of(FACTS_WITHOUT_CODEC_STRING)).build();

    assertThat(decision.videoSource()).contains(FACTS_WITHOUT_CODEC_STRING);
  }

  @Test
  @DisplayName("Should refuse a copy when the decision holds no playback facts")
  void shouldRefuseACopyWhenTheDecisionHoldsNoPlaybackFacts() {
    var decision = decisionBuilder(TranscodeMode.REMUX).videoSource(Optional.empty());

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codec string");
  }

  @Test
  @DisplayName("Should refuse a decision when it describes no video source")
  void shouldRefuseADecisionWhenItDescribesNoVideoSource() {
    var decision = decisionBuilder(TranscodeMode.FULL_TRANSCODE);

    assertThatThrownBy(() -> decision.videoSource(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("videoSource");
  }

  @Test
  @DisplayName(
      "Should refuse a decision when its encode codec profile belongs to another codec family")
  void shouldRefuseADecisionWhenItsEncodeCodecProfileBelongsToAnotherCodecFamily() {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.AV1_MAIN));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must belong to the decision's video codec family");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should refuse a decision that copies the video when it holds an encode codec profile")
  void shouldRefuseADecisionThatCopiesTheVideoWhenItHoldsAnEncodeCodecProfile(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode)
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot hold an encode codec profile");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modesAndTheirModeWithOtherAudio")
  @DisplayName(
      "Should keep the video path and follow the new audio when the decision takes other audio")
  void shouldKeepTheVideoPathAndFollowTheNewAudioWhenTheDecisionTakesOtherAudio(
      AudioChange change) {
    var decision =
        decisionBuilder(change.mode())
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .build();
    var audio =
        AudioDecision.stereoAac().toBuilder()
            .mode(change.audio())
            .sourceCodecString(Optional.of("mp4a.40.2"))
            .build();

    var withAudio = decision.withAudioDecision(audio);

    assertThat(withAudio.audioDecision()).isEqualTo(audio);
    assertThat(withAudio.transcodeMode()).isEqualTo(change.modeWithAudio());
    assertThat(withAudio.videoSource()).isEqualTo(decision.videoSource());
  }

  // Each video path once with audio that starts or stops passing through.
  static Stream<AudioChange> modesAndTheirModeWithOtherAudio() {
    return Stream.of(
        AudioChange.builder()
            .mode(TranscodeMode.REMUX)
            .audio(AudioMode.TRANSCODE)
            .modeWithAudio(TranscodeMode.AUDIO_TRANSCODE)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.AUDIO_TRANSCODE)
            .audio(AudioMode.COPY)
            .modeWithAudio(TranscodeMode.REMUX)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.VIDEO_TRANSCODE)
            .audio(AudioMode.TRANSCODE)
            .modeWithAudio(TranscodeMode.FULL_TRANSCODE)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.FULL_TRANSCODE)
            .audio(AudioMode.NONE)
            .modeWithAudio(TranscodeMode.VIDEO_TRANSCODE)
            .build());
  }

  @Builder
  record AudioChange(TranscodeMode mode, AudioMode audio, TranscodeMode modeWithAudio) {}

  private static TranscodeDecision.TranscodeDecisionBuilder decisionBuilder(TranscodeMode mode) {
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude());
  }
}
