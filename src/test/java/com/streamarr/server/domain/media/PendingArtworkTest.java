package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Pending Artwork Tests")
class PendingArtworkTest {

  private static final Instant EARLIER = Instant.parse("2026-10-14T10:00:00Z");
  private static final Instant LATER = EARLIER.plusSeconds(60);

  @Test
  @DisplayName("Should add the images and keep the earlier request time when either is earlier")
  void shouldAddTheImagesAndKeepTheEarlierRequestTimeWhenEitherIsEarlier() {
    var earlier = new PendingArtwork(2, EARLIER);
    var later = new PendingArtwork(3, LATER);

    assertThat(earlier.plus(later)).isEqualTo(new PendingArtwork(5, EARLIER));
    assertThat(later.plus(earlier)).isEqualTo(new PendingArtwork(5, EARLIER));
  }

  @Test
  @DisplayName("Should reject pending artwork when its first request time or the addend is missing")
  void shouldRejectPendingArtworkWhenItsFirstRequestTimeOrTheAddendIsMissing() {
    var pending = new PendingArtwork(1, EARLIER);

    assertThatNullPointerException().isThrownBy(() -> new PendingArtwork(1, null));
    assertThatNullPointerException().isThrownBy(() -> pending.plus(null));
  }
}
