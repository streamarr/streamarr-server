package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Artwork Steps Attempt Tests")
class ArtworkStepsAttemptTest {

  private static final ArtworkTarget MOVIE =
      new ArtworkTarget(UUID.randomUUID(), ImageEntityType.MOVIE);

  @Test
  @DisplayName("Should reject an attempt when its target or attempt time is missing")
  void shouldRejectAnAttemptWhenItsTargetOrAttemptTimeIsMissing() {
    var withoutTarget = ArtworkStepsAttempt.builder().generation(1).attemptedAt(Instant.EPOCH);
    var withoutAttemptTime = ArtworkStepsAttempt.builder().target(MOVIE).generation(1);

    assertThatNullPointerException().isThrownBy(withoutTarget::build);
    assertThatNullPointerException().isThrownBy(withoutAttemptTime::build);
  }

  @Test
  @DisplayName("Should belong to no library operation when the attempt names none")
  void shouldBelongToNoLibraryOperationWhenTheAttemptNamesNone() {
    var attempt =
        ArtworkStepsAttempt.builder()
            .target(MOVIE)
            .generation(1)
            .attemptedAt(Instant.EPOCH)
            .build();

    assertThat(attempt.libraryOperationId()).isEmpty();
  }
}
