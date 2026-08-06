package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @TempDir Path libraryPath;

  @Test
  @DisplayName("Should delete only movies with no files remaining after orphan cleanup")
  void shouldDeleteOnlyMoviesWithNoFilesRemainingAfterOrphanCleanup() throws IOException {
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
            mediaFile(library.getId(), deletedMovie.getId(), libraryPath.resolve("deleted.mkv")));
    var retainedMovieOrphan =
        mediaFileRepository.saveAndFlush(
            mediaFile(
                library.getId(), retainedMovie.getId(), libraryPath.resolve("missing-copy.mkv")));
    var retainedMovieFile =
        mediaFileRepository.saveAndFlush(
            mediaFile(library.getId(), retainedMovie.getId(), retainedPath));

    orphanedMediaFileCleanupService.onScanCompleted(new ScanCompletedEvent(library.getId()));

    assertThat(movieRepository.findById(deletedMovie.getId())).isEmpty();
    assertThat(movieRepository.findById(retainedMovie.getId())).isPresent();
    assertThat(mediaFileRepository.findById(deletedMovieOrphan.getId())).isEmpty();
    assertThat(mediaFileRepository.findById(retainedMovieOrphan.getId())).isEmpty();
    assertThat(mediaFileRepository.findById(retainedMovieFile.getId())).isPresent();
  }

  private static MediaFile mediaFile(java.util.UUID libraryId, java.util.UUID movieId, Path path) {
    return MediaFile.builder()
        .libraryId(libraryId)
        .mediaId(movieId)
        .filepathUri(FilepathCodec.encode(path))
        .filename(path.getFileName().toString())
        .status(MediaFileStatus.MATCHED)
        .build();
  }
}
