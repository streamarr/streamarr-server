package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Artwork Completion Evidence Tests")
class ArtworkCompletionEvidenceTest {

  private static final ArtworkTarget MOVIE =
      new ArtworkTarget(UUID.randomUUID(), ImageEntityType.MOVIE);

  @Test
  @DisplayName(
      "Should reject completion evidence when its target, pending request or results are missing")
  void shouldRejectCompletionEvidenceWhenItsTargetPendingRequestOrResultsAreMissing() {
    var none = Optional.<PendingArtworkRequest>empty();
    var noResults = List.<ItemResult>of();

    assertThatNullPointerException()
        .isThrownBy(() -> new ArtworkCompletionEvidence(null, none, noResults));
    assertThatNullPointerException()
        .isThrownBy(() -> new ArtworkCompletionEvidence(MOVIE, null, noResults));
    assertThatNullPointerException()
        .isThrownBy(() -> new ArtworkCompletionEvidence(MOVIE, none, null));
  }
}
