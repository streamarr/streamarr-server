package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Artwork Backlog Tests")
class ArtworkBacklogTest {

  private static final Instant OLDER = Instant.parse("2026-10-14T10:00:00Z");
  private static final Instant NEWER = OLDER.plusSeconds(60);

  @Test
  @DisplayName("Should add the pending images and keep the older request time when either is older")
  void shouldAddThePendingImagesAndKeepTheOlderRequestTimeWhenEitherIsOlder() {
    var older = new ArtworkBacklog(2, OLDER);
    var newer = new ArtworkBacklog(3, NEWER);

    assertThat(older.plus(newer)).isEqualTo(new ArtworkBacklog(5, OLDER));
    assertThat(newer.plus(older)).isEqualTo(new ArtworkBacklog(5, OLDER));
  }

  @Test
  @DisplayName(
      "Should reject an artwork backlog when its oldest request time or the addend is missing")
  void shouldRejectAnArtworkBacklogWhenItsOldestRequestTimeOrTheAddendIsMissing() {
    var backlog = new ArtworkBacklog(1, OLDER);

    assertThatNullPointerException().isThrownBy(() -> new ArtworkBacklog(1, null));
    assertThatNullPointerException().isThrownBy(() -> backlog.plus(null));
  }
}
