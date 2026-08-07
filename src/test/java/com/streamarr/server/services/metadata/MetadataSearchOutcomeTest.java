package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Metadata Search Outcome Tests")
class MetadataSearchOutcomeTest {

  @Test
  @DisplayName("Should reject null result when constructing found outcome")
  void shouldRejectNullResultWhenConstructingFoundOutcome() {
    assertThatThrownBy(() -> new Found(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should reject null cause when constructing temporarily unavailable outcome")
  void shouldRejectNullCauseWhenConstructingTemporarilyUnavailableOutcome() {
    assertThatThrownBy(() -> new TemporarilyUnavailable(null))
        .isInstanceOf(NullPointerException.class);
  }
}
