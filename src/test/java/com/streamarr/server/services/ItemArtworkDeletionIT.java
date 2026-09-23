package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ImageService.ProcessedImage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Item Artwork Deletion Integration Tests")
class ItemArtworkDeletionIT extends AbstractIntegrationTest {

  private static final Instant ATTEMPTED_AT = Instant.parse("2026-09-23T10:00:00Z");

  @Autowired private MovieService movieService;
  @Autowired private ImageService imageService;
  @Autowired private ImageRepository imageRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID movieId;
  private ProcessedImage poster;

  @BeforeEach
  void saveMovieWithPoster() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieId =
        movieRepository
            .saveAndFlush(Movie.builder().title("Deleted").library(library).build())
            .getId();
    poster = processedPoster("/poster.jpg");
    imageService.saveImages(poster.images(), ATTEMPTED_AT);
  }

  @AfterEach
  void removeMovie() {
    movieService.deleteMovieById(movieId);
    imageService.deleteFiles(poster.writtenFiles());
  }

  @Test
  @DisplayName("Should keep every artwork file when the delete rolls back")
  void shouldKeepEveryArtworkFileWhenTheDeleteRollsBack() {
    transactionTemplate.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          movieService.deleteMovieById(movieId);
        });

    assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
        .hasSize(ImageSize.values().length);
    assertThat(poster.writtenFiles()).allSatisfy(path -> assertThat(path).exists());
  }

  private ProcessedImage processedPoster(String key) {
    return imageService.processImage(
        createTestImage(600, 900), ImageType.POSTER, movieId, ImageEntityType.MOVIE, key);
  }
}
