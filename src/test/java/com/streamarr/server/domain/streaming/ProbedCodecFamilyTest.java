package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probed Codec Family Tests")
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
}
