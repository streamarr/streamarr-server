package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Encoder Backend Tests")
class EncoderBackendTest {

  private static final EncoderBackend.Kind SOFTWARE = EncoderBackend.Kind.SOFTWARE;

  @Test
  @DisplayName("Should reject construction when the codec family is missing")
  void shouldRejectConstructionWhenTheCodecFamilyIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new EncoderBackend(null, "libx264", SOFTWARE))
        .withMessageContaining("codecFamily");
  }

  @Test
  @DisplayName("Should reject construction when the encoder is missing")
  void shouldRejectConstructionWhenTheEncoderIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new EncoderBackend("h264", null, SOFTWARE))
        .withMessageContaining("encoder");
  }

  @Test
  @DisplayName("Should reject construction when the encoder kind is missing")
  void shouldRejectConstructionWhenTheEncoderKindIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new EncoderBackend("h264", "libx264", null))
        .withMessageContaining("kind");
  }
}
