package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
    assertThatThrownBy(
            () ->
                observation
                    .renditions()
                    .add(new RenditionObservation("360p", RenditionState.STARTING)))
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
  @DisplayName("Should reject job observation when rendition labels are duplicated")
  void shouldRejectJobObservationWhenRenditionLabelsAreDuplicated() {
    var duplicateRenditions =
        List.of(
            new RenditionObservation("720p", RenditionState.RUNNING),
            new RenditionObservation("720p", RenditionState.FAILED));

    assertThatThrownBy(
            () ->
                new TranscodeJobObservation(
                    new TranscodeJobRef(UUID.randomUUID(), 1),
                    TranscodeJobState.FAILED,
                    duplicateRenditions))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
