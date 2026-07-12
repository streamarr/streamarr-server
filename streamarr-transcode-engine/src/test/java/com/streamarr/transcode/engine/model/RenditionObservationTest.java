package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Rendition Observation Tests")
class RenditionObservationTest {

  @Test
  @DisplayName("Should preserve rendition state when observation is valid")
  void shouldPreserveRenditionStateWhenObservationIsValid() {
    var observation = new RenditionObservation("720p", RenditionState.RUNNING);

    assertThat(observation.label()).isEqualTo("720p");
    assertThat(observation.state()).isEqualTo(RenditionState.RUNNING);
  }

  @Test
  @DisplayName("Should reject rendition observation when identity or state is absent")
  void shouldRejectRenditionObservationWhenIdentityOrStateIsAbsent() {
    assertThatThrownBy(() -> new RenditionObservation(null, RenditionState.RUNNING))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RenditionObservation(" ", RenditionState.RUNNING))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RenditionObservation("720p", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"720/p", "720\\p", ".", "..", "720\0p"})
  @DisplayName("Should reject rendition observation when label is not one portable segment")
  void shouldRejectRenditionObservationWhenLabelIsNotOnePortableSegment(String label) {
    assertThatThrownBy(() -> new RenditionObservation(label, RenditionState.RUNNING))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
