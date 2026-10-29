package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probed video codec")
class ProbedVideoCodecTest {

  @ParameterizedTest
  @CsvSource({"h264, H264", "hevc, HEVC", "av1, AV1"})
  @DisplayName("Should read a codec that the server has rules for when the probe names it")
  void shouldReadACodecThatTheServerHasRulesForWhenTheProbeNamesIt(
      String name, ProbedVideoCodec codec) {
    assertThat(ProbedVideoCodec.fromProbedName(name)).isEqualTo(codec);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "vp9", "mpeg2video", "H264", "hevc "})
  @DisplayName("Should read as another codec when the server has no rules for the probed name")
  void shouldReadAsAnotherCodecWhenTheServerHasNoRulesForTheProbedName(String name) {
    assertThat(ProbedVideoCodec.fromProbedName(name)).isEqualTo(ProbedVideoCodec.OTHER);
  }

  @ParameterizedTest
  @CsvSource({"H264, true", "HEVC, true", "AV1, true", "OTHER, false"})
  @DisplayName("Should say whether the probe walks a codec when asked for any codec")
  void shouldSayWhetherTheProbeWalksACodecWhenAskedForAnyCodec(
      ProbedVideoCodec codec, boolean walked) {
    assertThat(codec.isWalkedByProbe()).isEqualTo(walked);
  }

  @ParameterizedTest
  @CsvSource({"H264, false", "HEVC, true", "AV1, true", "OTHER, false"})
  @DisplayName("Should say whether an RPU header alone marks a baseless profile when asked")
  void shouldSayWhetherAnRpuHeaderAloneMarksABaselessProfileWhenAsked(
      ProbedVideoCodec codec, boolean recordLessBaseless) {
    assertThat(codec.hasRecordLessBaselessProfile()).isEqualTo(recordLessBaseless);
  }
}
