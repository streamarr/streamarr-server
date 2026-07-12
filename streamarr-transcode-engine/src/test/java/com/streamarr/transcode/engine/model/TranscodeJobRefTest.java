package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Job Reference Tests")
class TranscodeJobRefTest {

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject job reference when generation is not positive")
  void shouldRejectJobReferenceWhenGenerationIsNotPositive(long generation) {
    assertThatThrownBy(() -> new TranscodeJobRef(UUID.randomUUID(), generation))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject job reference when job identity is absent")
  void shouldRejectJobReferenceWhenJobIdentityIsAbsent() {
    assertThatThrownBy(() -> new TranscodeJobRef(null, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
