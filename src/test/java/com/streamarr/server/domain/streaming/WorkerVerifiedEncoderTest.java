package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker-Verified Encoder Tests")
class WorkerVerifiedEncoderTest {

  @Test
  @DisplayName("Should reject construction when the encoder backend is missing")
  void shouldRejectConstructionWhenTheEncoderBackendIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> WorkerVerifiedEncoder.builder().keyframeVerified(true).build())
        .withMessageContaining("backend");
  }

  @Test
  @DisplayName("Should reject construction when the encode features are missing")
  void shouldRejectConstructionWhenTheEncodeFeaturesAreMissing() {
    var encoder =
        WorkerVerifiedEncoder.builder()
            .backend(
                EncoderBackend.builder()
                    .codecFamily("av1")
                    .encoder("libsvtav1")
                    .kind(EncoderBackend.Kind.SOFTWARE)
                    .build());

    assertThatNullPointerException()
        .isThrownBy(() -> encoder.encodeFeatures(null).build())
        .withMessageContaining("encodeFeatures");
  }
}
