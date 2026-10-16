package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stored Artwork Request Tests")
class StoredArtworkRequestTest {

  private static final ArtworkSources MOVIE_ARTWORK =
      ArtworkSources.builder()
          .targetId(UUID.randomUUID())
          .targetType(ImageEntityType.MOVIE)
          .sources(List.of())
          .build();

  @Test
  @DisplayName("Should reject a stored request when its artwork or refresh mode is missing")
  void shouldRejectAStoredRequestWhenItsArtworkOrRefreshModeIsMissing() {
    var withoutArtwork =
        StoredArtworkRequest.builder().refreshMode(ImageRefreshMode.PRESERVE).generation(1);
    var withoutRefreshMode = StoredArtworkRequest.builder().artwork(MOVIE_ARTWORK).generation(1);

    assertThatNullPointerException().isThrownBy(withoutArtwork::build);
    assertThatNullPointerException().isThrownBy(withoutRefreshMode::build);
  }

  @Test
  @DisplayName("Should belong to no library operation when the stored request names none")
  void shouldBelongToNoLibraryOperationWhenTheStoredRequestNamesNone() {
    var request =
        StoredArtworkRequest.builder()
            .artwork(MOVIE_ARTWORK)
            .refreshMode(ImageRefreshMode.PRESERVE)
            .generation(1)
            .build();

    assertThat(request.libraryOperationId()).isEqualTo(Optional.empty());
  }
}
