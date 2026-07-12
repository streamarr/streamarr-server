package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Tests")
class TranscodeDecisionTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("incompleteDecisions")
  @DisplayName("Should reject transcode decision when a required value is absent")
  void shouldRejectTranscodeDecisionWhenRequiredValueIsAbsent(
      String scenario, Supplier<TranscodeDecision> decision) {
    assertThatThrownBy(decision::get).isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> incompleteDecisions() {
    return Stream.of(
        Arguments.of(
            "transcode mode",
            (Supplier<TranscodeDecision>) () -> validBuilder().transcodeMode(null).build()),
        Arguments.of(
            "video codec",
            (Supplier<TranscodeDecision>) () -> validBuilder().videoCodecFamily(null).build()),
        Arguments.of(
            "blank video codec",
            (Supplier<TranscodeDecision>) () -> validBuilder().videoCodecFamily(" ").build()),
        Arguments.of(
            "audio decision",
            (Supplier<TranscodeDecision>) () -> validBuilder().audioDecision(null).build()),
        Arguments.of(
            "subtitle decision",
            (Supplier<TranscodeDecision>) () -> validBuilder().subtitleDecision(null).build()),
        Arguments.of(
            "container format",
            (Supplier<TranscodeDecision>) () -> validBuilder().containerFormat(null).build()));
  }

  private static TranscodeDecision.TranscodeDecisionBuilder validBuilder() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.MPEGTS);
  }
}
