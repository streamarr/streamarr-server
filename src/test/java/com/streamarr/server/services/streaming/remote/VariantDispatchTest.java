package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Variant Dispatch Tests")
class VariantDispatchTest {

  @Test
  @DisplayName("Should reject construction when the encoder is missing")
  void shouldRejectConstructionWhenTheEncoderIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new VariantDispatch(null))
        .withMessageContaining("encoder");
  }
}
