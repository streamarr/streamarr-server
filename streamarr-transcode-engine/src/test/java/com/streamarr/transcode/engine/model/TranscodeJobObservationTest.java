package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Job Observation Tests")
class TranscodeJobObservationTest {

  @Test
  @DisplayName("Should preserve an immutable rendition snapshot when job observation is valid")
  void shouldPreserveImmutableRenditionSnapshotWhenJobObservationIsValid() {
    var renditions =
        new ArrayList<>(List.of(new RenditionObservation("720p", RenditionState.RUNNING)));

    var observation =
        TranscodeJobObservation.builder()
            .jobRef(new TranscodeJobRef(UUID.randomUUID(), 1))
            .state(TranscodeJobState.RUNNING)
            .renditions(renditions)
            .build();
    renditions.add(new RenditionObservation("480p", RenditionState.STARTING));

    assertThat(observation.renditions())
        .extracting(RenditionObservation::label)
        .containsExactly("720p");
    var snapshot = observation.renditions();
    var addedRendition = new RenditionObservation("360p", RenditionState.STARTING);

    assertThatThrownBy(() -> snapshot.add(addedRendition))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("Should reject job observation when a required value is absent")
  void shouldRejectJobObservationWhenRequiredValueIsAbsent() {
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    var renditions = List.of(new RenditionObservation("720p", RenditionState.RUNNING));

    assertThatThrownBy(
            () -> new TranscodeJobObservation(null, TranscodeJobState.RUNNING, renditions))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TranscodeJobObservation(jobRef, null, renditions))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TranscodeJobObservation(jobRef, TranscodeJobState.RUNNING, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject job observation when a rendition entry is absent")
  void shouldRejectJobObservationWhenRenditionEntryIsAbsent() {
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    var renditions = java.util.Collections.<RenditionObservation>singletonList(null);

    assertThatThrownBy(
            () -> new TranscodeJobObservation(jobRef, TranscodeJobState.RUNNING, renditions))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Transcode job observation values are required");
  }

  @Test
  @DisplayName("Should reject job observation when rendition labels are duplicated")
  void shouldRejectJobObservationWhenRenditionLabelsAreDuplicated() {
    var duplicateRenditions =
        List.of(
            new RenditionObservation("720p", RenditionState.STOPPED),
            new RenditionObservation("720p", RenditionState.FAILED));
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);

    assertThatThrownBy(
            () ->
                new TranscodeJobObservation(jobRef, TranscodeJobState.FAILED, duplicateRenditions))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject observation labels that collide on case-insensitive storage")
  void shouldRejectObservationLabelsThatCollideOnCaseInsensitiveStorage() {
    var collidingRenditions =
        List.of(
            new RenditionObservation("720p", RenditionState.STOPPED),
            new RenditionObservation("720P", RenditionState.FAILED));
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);

    assertThatThrownBy(
            () ->
                new TranscodeJobObservation(jobRef, TranscodeJobState.FAILED, collidingRenditions))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("contradictoryStates")
  @DisplayName("Should reject job observation when state contradicts rendition snapshot")
  void shouldRejectJobObservationWhenStateContradictsRenditionSnapshot(
      TranscodeJobState state, List<RenditionObservation> renditions) {
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);

    assertThatThrownBy(() -> new TranscodeJobObservation(jobRef, state, renditions))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("validMixedStates")
  @DisplayName("Should accept job observation when mixed rendition states agree")
  void shouldAcceptJobObservationWhenMixedRenditionStatesAgree(
      TranscodeJobState state, List<RenditionObservation> renditions) {
    var observation =
        new TranscodeJobObservation(new TranscodeJobRef(UUID.randomUUID(), 1), state, renditions);

    assertThat(observation.state()).isEqualTo(state);
    assertThat(observation.renditions()).containsExactlyElementsOf(renditions);
  }

  static Stream<Arguments> contradictoryStates() {
    return Stream.of(
        Arguments.of(
            TranscodeJobState.ABSENT,
            List.of(new RenditionObservation("720p", RenditionState.STOPPED))),
        Arguments.of(
            TranscodeJobState.ADMITTING,
            List.of(new RenditionObservation("720p", RenditionState.RUNNING))),
        Arguments.of(TranscodeJobState.RUNNING, List.of()),
        Arguments.of(
            TranscodeJobState.RUNNING,
            List.of(new RenditionObservation("720p", RenditionState.FAILED))),
        Arguments.of(
            TranscodeJobState.RUNNING,
            List.of(new RenditionObservation("720p", RenditionState.COMPLETED))),
        Arguments.of(
            TranscodeJobState.RUNNING,
            List.of(
                new RenditionObservation("720p", RenditionState.RUNNING),
                new RenditionObservation("480p", RenditionState.FAILED))),
        Arguments.of(
            TranscodeJobState.RUNNING,
            List.of(
                new RenditionObservation("720p", RenditionState.RUNNING),
                new RenditionObservation("480p", RenditionState.STARTING))),
        Arguments.of(
            TranscodeJobState.COMPLETED,
            List.of(new RenditionObservation("720p", RenditionState.RUNNING))),
        Arguments.of(
            TranscodeJobState.FAILED,
            List.of(new RenditionObservation("720p", RenditionState.STOPPED))),
        Arguments.of(
            TranscodeJobState.FAILED,
            List.of(
                new RenditionObservation("720p", RenditionState.FAILED),
                new RenditionObservation("480p", RenditionState.RUNNING))),
        Arguments.of(
            TranscodeJobState.FAILED,
            List.of(
                new RenditionObservation("720p", RenditionState.FAILED),
                new RenditionObservation("480p", RenditionState.STARTING))),
        Arguments.of(
            TranscodeJobState.STOPPED,
            List.of(new RenditionObservation("720p", RenditionState.RUNNING))));
  }

  static Stream<Arguments> validMixedStates() {
    return Stream.of(
        Arguments.of(
            TranscodeJobState.RUNNING,
            List.of(
                new RenditionObservation("720p", RenditionState.RUNNING),
                new RenditionObservation("480p", RenditionState.COMPLETED))),
        Arguments.of(
            TranscodeJobState.FAILED,
            List.of(
                new RenditionObservation("720p", RenditionState.FAILED),
                new RenditionObservation("480p", RenditionState.COMPLETED))));
  }
}
