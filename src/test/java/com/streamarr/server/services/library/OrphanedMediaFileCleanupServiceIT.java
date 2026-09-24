package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Orphaned Media File Cleanup Service Integration Tests")
class OrphanedMediaFileCleanupServiceIT extends AbstractIntegrationTest {

  @Autowired private OrphanedMediaFileCleanupService orphanedMediaFileCleanupService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private ImageService imageService;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ItemResultRepository itemResults;

  @TempDir Path libraryPath;

  @Test
  @DisplayName("Should delete only movies with no files remaining when orphans are cleaned up")
  void shouldDeleteOnlyMoviesWithNoFilesRemainingWhenOrphansAreCleanedUp() throws IOException {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(libraryPath))
                .build());
    var deletedMovie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Deleted Movie").library(library).build());
    var retainedMovie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Retained Movie").library(library).build());
    var retainedPath = Files.createFile(libraryPath.resolve("retained.mkv"));

    var deletedMovieOrphan =
        mediaFileRepository.saveAndFlush(
            mediaFileBuilder(libraryPath.resolve("deleted.mkv"))
                .libraryId(library.getId())
                .mediaId(deletedMovie.getId())
                .build());
    var retainedMovieOrphan =
        mediaFileRepository.saveAndFlush(
            mediaFileBuilder(libraryPath.resolve("missing-copy.mkv"))
                .libraryId(library.getId())
                .mediaId(retainedMovie.getId())
                .build());
    var retainedMovieFile =
        mediaFileRepository.saveAndFlush(
            mediaFileBuilder(retainedPath)
                .libraryId(library.getId())
                .mediaId(retainedMovie.getId())
                .build());

    orphanedMediaFileCleanupService.onScanCompleted(new ScanCompletedEvent(library.getId()));

    assertThat(movieRepository.findById(deletedMovie.getId())).isEmpty();
    assertThat(movieRepository.findById(retainedMovie.getId())).isPresent();
    assertThat(mediaFileRepository.findById(deletedMovieOrphan.getId())).isEmpty();
    assertThat(mediaFileRepository.findById(retainedMovieOrphan.getId())).isEmpty();
    assertThat(mediaFileRepository.findById(retainedMovieFile.getId())).isPresent();
  }

  @Test
  @DisplayName("Should remove the results and artwork of a movie when its files are gone")
  void shouldRemoveTheResultsAndArtworkOfAMovieWhenItsFilesAreGone() {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(libraryPath))
                .build());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Missing").library(library).build());
    mediaFileRepository.saveAndFlush(
        mediaFileBuilder(libraryPath.resolve("missing.mkv"))
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .build());
    var poster =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            movie.getId(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    imageService.saveImages(poster.images(), Instant.parse("2026-09-23T10:00:00Z"));
    assertThat(itemResults.findByItem(movie.getId(), ImageEntityType.MOVIE)).isNotEmpty();

    orphanedMediaFileCleanupService.onScanCompleted(new ScanCompletedEvent(library.getId()));

    assertThat(movieRepository.findById(movie.getId())).isEmpty();
    assertThat(itemResults.findByItem(movie.getId(), ImageEntityType.MOVIE)).isEmpty();
    assertThat(imageRepository.findByEntityIdAndEntityType(movie.getId(), ImageEntityType.MOVIE))
        .isEmpty();
    assertThat(poster.writtenFiles())
        .isNotEmpty()
        .allSatisfy(path -> assertThat(path).doesNotExist());
  }

  private static MediaFile.MediaFileBuilder<?, ?> mediaFileBuilder(Path path) {
    return MediaFile.builder()
        .filepathUri(FilepathCodec.encode(path))
        .filename(path.getFileName().toString())
        .status(MediaFileStatus.MATCHED);
  }
}
