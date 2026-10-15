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
        .isThrownBy(() -> new WorkerVerifiedEncoder(null, true))
        .withMessageContaining("backend");
  }
}
