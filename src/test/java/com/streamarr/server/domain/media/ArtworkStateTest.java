package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Artwork State Tests")
class ArtworkStateTest {

  private static final ArtworkTarget MOVIE =
      new ArtworkTarget(UUID.randomUUID(), ImageEntityType.MOVIE);

  @Test
  @DisplayName("Should reject a state when its target, pending generation or results are missing")
  void shouldRejectAStateWhenItsTargetPendingGenerationOrResultsAreMissing() {
    var none = Optional.<Long>empty();
    var noResults = List.<ItemResult>of();

    assertThatNullPointerException().isThrownBy(() -> new ArtworkState(null, none, noResults));
    assertThatNullPointerException().isThrownBy(() -> new ArtworkState(MOVIE, null, noResults));
    assertThatNullPointerException().isThrownBy(() -> new ArtworkState(MOVIE, none, null));
  }
}
