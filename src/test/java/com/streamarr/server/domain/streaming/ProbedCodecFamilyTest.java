package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probed codec family")
class ProbedCodecFamilyTest {

  @ParameterizedTest
  @CsvSource({"h264, H264", "hevc, HEVC", "av1, AV1"})
  @DisplayName("Should read a codec family that the server has rules for when the probe names it")
  void shouldReadACodecFamilyThatTheServerHasRulesForWhenTheProbeNamesIt(
      String name, ProbedCodecFamily codecFamily) {
    assertThat(ProbedCodecFamily.fromProbedName(name)).isEqualTo(codecFamily);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "vp9", "mpeg2video", "H264", "hevc "})
  @DisplayName(
      "Should read as another codec family when the server has no rules for the probed name")
  void shouldReadAsAnotherCodecFamilyWhenTheServerHasNoRulesForTheProbedName(String name) {
    assertThat(ProbedCodecFamily.fromProbedName(name)).isEqualTo(ProbedCodecFamily.OTHER);
  }

  @ParameterizedTest
  @CsvSource({"H264, true", "HEVC, true", "AV1, true", "OTHER, false"})
  @DisplayName("Should say whether the probe walks a codec family when asked for any family")
  void shouldSayWhetherTheProbeWalksACodecFamilyWhenAskedForAnyFamily(
      ProbedCodecFamily codecFamily, boolean walked) {
    assertThat(codecFamily.isWalkedByProbe()).isEqualTo(walked);
  }

  @ParameterizedTest
  @CsvSource({"H264, false", "HEVC, true", "AV1, true", "OTHER, false"})
  @DisplayName("Should say whether an RPU header alone marks a baseless profile when asked")
  void shouldSayWhetherAnRpuHeaderAloneMarksABaselessProfileWhenAsked(
      ProbedCodecFamily codecFamily, boolean recordLessBaseless) {
    assertThat(codecFamily.hasRecordLessBaselessProfile()).isEqualTo(recordLessBaseless);
  }
}
