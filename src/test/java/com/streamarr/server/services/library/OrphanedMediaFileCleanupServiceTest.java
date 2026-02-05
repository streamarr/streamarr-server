package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.MovieService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Orphaned Media File Cleanup Service Tests")
public class OrphanedMediaFileCleanupServiceTest {

  private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
  private final MediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
  private final MovieRepository fakeMovieRepository = new FakeMovieRepository();
  private final MovieService movieService = new MovieService(fakeMovieRepository, null, null);
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

  private final OrphanedMediaFileCleanupService orphanedMediaFileCleanupService =
      new OrphanedMediaFileCleanupService(
          fakeLibraryRepository, fakeMediaFileRepository, movieService, fileSystem);

  private Library library;

  @BeforeEach
  void setup() throws IOException {
    library = fakeLibraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());

    Files.createDirectories(fileSystem.getPath(library.getFilepath()));
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should remove orphaned media file when file no longer exists on disk")
  void shouldRemoveOrphanedMediaFileWhenFileNoLongerExistsOnDisk() {
    var orphanedMediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepath("/library/nonexistent/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMediaFileRepository.findById(orphanedMediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should not remove media file when file still exists on disk")
  void shouldNotRemoveMediaFileWhenFileStillExistsOnDisk() throws IOException {
    var moviePath = createMovieFile("Inception", "Inception (2010).mkv");

    var existingMediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepath(moviePath.toAbsolutePath().toString())
                .filename("Inception (2010).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMediaFileRepository.findById(existingMediaFile.getId())).isPresent();
  }

  @Test
  @DisplayName("Should only remove orphaned media files for the scanned library")
  void shouldOnlyRemoveOrphanedMediaFilesForScannedLibrary() {
    var otherLibraryId = UUID.randomUUID();

    var otherLibraryFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(otherLibraryId)
                .filepath("/other/library/nonexistent.mkv")
                .filename("nonexistent.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMediaFileRepository.findById(otherLibraryFile.getId())).isPresent();
  }

  @Test
  @DisplayName("Should delete movie when all its media files are orphaned")
  void shouldDeleteMovieWhenAllItsMediaFilesAreOrphaned() {
    var movie = fakeMovieRepository.save(Movie.builder().title("Gone Movie").build());

    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .filepath("/library/nonexistent/gone-movie.mkv")
            .filename("gone-movie.mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMovieRepository.findById(movie.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should not delete movie when it still has remaining media files")
  void shouldNotDeleteMovieWhenItStillHasRemainingMediaFiles() throws IOException {
    var existingPath = createMovieFile("Surviving Movie", "Surviving Movie (2020).mkv");

    var movie = fakeMovieRepository.save(Movie.builder().title("Surviving Movie").build());

    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .filepath(existingPath.toAbsolutePath().toString())
            .filename("Surviving Movie (2020).mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .filepath("/library/nonexistent/surviving-movie-copy.mkv")
            .filename("surviving-movie-copy.mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMovieRepository.findById(movie.getId())).isPresent();
  }

  private Path createMovieFile(String folder, String filename) throws IOException {
    var rootPath = fileSystem.getPath(library.getFilepath());
    var movieFolder = rootPath.resolve(folder);
    Files.createDirectory(movieFolder);
    var movieFile = movieFolder.resolve(filename);
    Files.createFile(movieFile);
    return movieFile;
  }
}
