package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Job Specification Tests")
class TranscodeJobSpecTest {

  @Test
  @DisplayName("Should preserve an immutable rendition ladder when job specification is valid")
  void shouldPreserveImmutableRenditionLadderWhenJobSpecificationIsValid() {
    var renditions = new ArrayList<>(List.of(rendition("720p")));

    var specification = specification(renditions);
    renditions.add(rendition("480p"));

    assertThat(specification.renditions()).extracting(RenditionSpec::label).containsExactly("720p");
    assertThatThrownByUnsupportedMutation(specification.renditions());
  }

  @Test
  @DisplayName("Should reject job specification when rendition ladder is empty")
  void shouldRejectJobSpecificationWhenRenditionLadderIsEmpty() {
    assertThatThrownBy(() -> specification(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject job specification when rendition ladder contains an absent value")
  void shouldRejectJobSpecificationWhenRenditionLadderContainsAbsentValue() {
    assertThatThrownBy(() -> specification(java.util.Arrays.asList(rendition("720p"), null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject job specification when rendition labels are duplicated")
  void shouldRejectJobSpecificationWhenRenditionLabelsAreDuplicated() {
    assertThatThrownBy(() -> specification(List.of(rendition("720p"), rendition("720p"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject rendition labels that collide on case-insensitive storage")
  void shouldRejectRenditionLabelsThatCollideOnCaseInsensitiveStorage() {
    assertThatThrownBy(() -> specification(List.of(rendition("720p"), rendition("720P"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingRequiredValues")
  @DisplayName("Should reject job specification when a required value is absent")
  void shouldRejectJobSpecificationWhenRequiredValueIsAbsent(
      String scenario, Supplier<TranscodeJobSpec> specification) {
    assertThatThrownBy(specification::get).isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> missingRequiredValues() {
    return Stream.of(
        Arguments.of(
            "session identity",
            (Supplier<TranscodeJobSpec>) () -> validBuilder().sessionId(null).build()),
        Arguments.of(
            "job reference",
            (Supplier<TranscodeJobSpec>) () -> validBuilder().jobRef(null).build()),
        Arguments.of(
            "media source", (Supplier<TranscodeJobSpec>) () -> validBuilder().source(null).build()),
        Arguments.of(
            "stream decision",
            (Supplier<TranscodeJobSpec>) () -> validBuilder().decision(null).build()),
        Arguments.of(
            "execution parameters",
            (Supplier<TranscodeJobSpec>) () -> validBuilder().execution(null).build()),
        Arguments.of(
            "rendition ladder",
            (Supplier<TranscodeJobSpec>) () -> validBuilder().renditions(null).build()));
  }

  private static void assertThatThrownByUnsupportedMutation(List<RenditionSpec> renditions) {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> renditions.add(rendition("360p")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static TranscodeJobSpec specification(List<RenditionSpec> renditions) {
    return validBuilder().renditions(renditions).build();
  }

  private static TranscodeJobSpec.TranscodeJobSpecBuilder validBuilder() {
    return TranscodeJobSpec.builder()
        .sessionId(UUID.randomUUID())
        .jobRef(new TranscodeJobRef(UUID.randomUUID(), 1))
        .source(new MediaSourceRef(UUID.randomUUID(), "Movies/movie.mkv"))
        .decision(validDecision().build())
        .execution(new TranscodeExecutionParameters(0, 6, 23.976, 0, Duration.ofSeconds(45)))
        .renditions(List.of(rendition("720p")));
  }

  private static TranscodeDecision.TranscodeDecisionBuilder validDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.MPEGTS);
  }

  private static RenditionSpec rendition(String label) {
    return new RenditionSpec(label, 1280, 720, 3_000_000L);
  }
}
