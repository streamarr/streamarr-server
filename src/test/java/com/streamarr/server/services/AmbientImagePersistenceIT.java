package com.streamarr.server.services;

import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.fixtures.ArtworkStepsAttemptFixture;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ImageService.ProcessedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Ambient Image Persistence Integration Tests")
class AmbientImagePersistenceIT extends AbstractIntegrationTest {

  @Autowired private ImageService imageService;
  @Autowired private MovieService movieService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ArtworkRequestRepository artworkRequests;

  private UUID entityId;

  @BeforeEach
  void saveMovie() {
    entityId = SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
  }

  @AfterEach
  void removeMovie() {
    movieService.tryDeleteMovieById(entityId);
  }

  @Test
  @DisplayName("Should preserve every named ambient swatch when artwork is saved")
  void shouldPreserveEveryNamedAmbientSwatchWhenArtworkIsSaved() {
    var expected = colors().build();

    imageService.saveImages(artwork(expected).images(), requestedAttempt());

    assertThat(readAmbientColors()).contains(expected);
  }

  @Test
  @DisplayName("Should replace every named ambient swatch when artwork is replaced")
  void shouldReplaceEveryNamedAmbientSwatchWhenArtworkIsReplaced() {
    imageService.saveImages(artwork(colors().build()).images(), requestedAttempt());
    var expected =
        colors()
            .primary("#e9b658")
            .darkVibrant("#603010")
            .darkMuted("#483830")
            .lightVibrant("#f0c068")
            .lightMuted("#d8c8a8")
            .build();

    imageService.replaceImages(artwork(expected), requestedAttempt());

    assertThat(readAmbientColors()).contains(expected);
  }

  @Test
  @DisplayName("Should preserve legacy ambient colors when target swatches are absent")
  void shouldPreserveLegacyAmbientColorsWhenTargetSwatchesAreAbsent() {
    var expected =
        colors().darkVibrant(null).darkMuted(null).lightVibrant(null).lightMuted(null).build();

    imageService.saveImages(artwork(expected).images(), requestedAttempt());

    assertThat(readAmbientColors()).contains(expected);
  }

  @Test
  @DisplayName("Should omit ambient colors when artwork has no palette")
  void shouldOmitAmbientColorsWhenArtworkHasNoPalette() {
    imageService.saveImages(artwork(null).images(), requestedAttempt());

    assertThat(readAmbientColors()).isEmpty();
  }

  private ArtworkStepsAttempt requestedAttempt() {
    return ArtworkStepsAttemptFixture.requestedAttemptFor(
        transactions, artworkRequests, new ArtworkTarget(entityId, ImageEntityType.MOVIE));
  }

  private Optional<AmbientColors> readAmbientColors() {
    return transactions.execute(
        _ ->
            imageService.findByEntity(entityId, ImageEntityType.MOVIE).stream()
                .filter(image -> image.getVariant() == ImageSize.SMALL)
                .findFirst()
                .orElseThrow()
                .getAmbientColors());
  }

  private ProcessedImage artwork(AmbientColors colors) {
    var images =
        Arrays.stream(ImageSize.values())
            .<Image>map(
                size ->
                    imageBuilder(entityId)
                        .id(UUID.randomUUID())
                        .key("ambient-poster")
                        .variant(size)
                        .path(entityId + "/" + UUID.randomUUID() + ".jpg")
                        .ambientColors(
                            size == ImageSize.SMALL
                                ? Optional.ofNullable(colors)
                                : Optional.empty())
                        .build())
            .toList();
    return new ProcessedImage(images, List.of());
  }

  private static AmbientColors.AmbientColorsBuilder colors() {
    return AmbientColors.builder()
        .topLeft("#010101")
        .topRight("#020202")
        .bottomRight("#030303")
        .bottomLeft("#040404")
        .primary("#00a0a0")
        .darkVibrant("#103070")
        .darkMuted("#283830")
        .lightVibrant("#68f8f8")
        .lightMuted("#c8d0c8");
  }
}
