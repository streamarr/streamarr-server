package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
  @DisplayName("Should accept a copy when the probe version reports no playback facts")
  void shouldAcceptACopyWhenTheProbeVersionReportsNoPlaybackFacts() {
    var decision = decisionBuilder(TranscodeMode.REMUX).build();

    assertThat(decision.videoSource()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a decision when it describes no video source")
  void shouldRefuseADecisionWhenItDescribesNoVideoSource() {
    var decision = decisionBuilder(TranscodeMode.FULL_TRANSCODE);

    assertThatThrownBy(() -> decision.videoSource(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("videoSource");
  }

  private static TranscodeDecision.TranscodeDecisionBuilder decisionBuilder(TranscodeMode mode) {
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude());
  }
}
