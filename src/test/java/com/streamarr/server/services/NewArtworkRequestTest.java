package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("New Artwork Request Tests")
class NewArtworkRequestTest {

  private static final ArtworkSources MOVIE_ARTWORK =
      ArtworkSources.builder()
          .targetId(UUID.randomUUID())
          .targetType(ImageEntityType.MOVIE)
          .sources(List.of())
          .build();

  @Test
  @DisplayName(
      "Should reject a new request when its artwork, refresh mode or request time is missing")
  void shouldRejectANewRequestWhenItsArtworkRefreshModeOrRequestTimeIsMissing() {
    var withoutArtwork =
        NewArtworkRequest.builder()
            .refreshMode(ImageRefreshMode.PRESERVE)
            .requestedAt(Instant.EPOCH);
    var withoutRefreshMode =
        NewArtworkRequest.builder().artwork(MOVIE_ARTWORK).requestedAt(Instant.EPOCH);
    var withoutRequestTime =
        NewArtworkRequest.builder().artwork(MOVIE_ARTWORK).refreshMode(ImageRefreshMode.PRESERVE);

    assertThatNullPointerException().isThrownBy(withoutArtwork::build);
    assertThatNullPointerException().isThrownBy(withoutRefreshMode::build);
    assertThatNullPointerException().isThrownBy(withoutRequestTime::build);
  }

  @Test
  @DisplayName("Should belong to no library operation when the new request names none")
  void shouldBelongToNoLibraryOperationWhenTheNewRequestNamesNone() {
    var request =
        NewArtworkRequest.builder()
            .artwork(MOVIE_ARTWORK)
            .refreshMode(ImageRefreshMode.PRESERVE)
            .requestedAt(Instant.EPOCH)
            .build();

    assertThat(request.libraryOperationId()).isEqualTo(Optional.empty());
  }
}
