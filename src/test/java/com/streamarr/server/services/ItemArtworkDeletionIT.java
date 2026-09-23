package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ImageService.ProcessedImage;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Item Artwork Deletion Integration Tests")
class ItemArtworkDeletionIT extends AbstractIntegrationTest {

  private static final Instant ATTEMPTED_AT = Instant.parse("2026-09-23T10:00:00Z");

  @Autowired private MovieService movieService;
  @Autowired private ImageService imageService;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private UUID movieId;
  private ProcessedImage poster;

  @BeforeEach
  void saveMovieWithPoster() {
    movieId = SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
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

  @Test
  @DisplayName("Should delete the movie and all its artwork when a replacement holds it first")
  void shouldDeleteTheMovieAndAllItsArtworkWhenAReplacementHoldsItFirst() throws Exception {
    var replacement = processedPoster("/replacement.jpg");
    var posterRow =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("image")
            .rowId(poster.images().getFirst().getId())
            .build();

    try (var imageLock = lockRow(posterRow);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var replace = executor.submit(() -> imageService.replaceImages(replacement, ATTEMPTED_AT));
      awaitWaitersBehind(jdbcTemplate, imageLock.backendPid(), 1);
      var delete = executor.submit(() -> movieService.deleteMovieById(movieId));
      awaitWaitersBehind(jdbcTemplate, imageLock.backendPid(), 2);
      imageLock.release();

      replace.get(10, TimeUnit.SECONDS);
      delete.get(10, TimeUnit.SECONDS);
    }

    assertThat(movieRepository.findById(movieId)).isEmpty();
    assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
        .isEmpty();
    assertThat(itemResults.findByItem(movieId, ImageEntityType.MOVIE)).isEmpty();
    assertThat(poster.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject a replacement and delete its files when the movie was deleted")
  void shouldRejectAReplacementAndDeleteItsFilesWhenTheMovieWasDeleted() {
    var replacement = processedPoster("/replacement.jpg");
    movieService.deleteMovieById(movieId);

    assertThatThrownBy(() -> imageService.replaceImages(replacement, ATTEMPTED_AT))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
        .isEmpty();
    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  private ProcessedImage processedPoster(String key) {
    return imageService.processImage(
        createTestImage(600, 900), ImageType.POSTER, movieId, ImageEntityType.MOVIE, key);
  }
}
