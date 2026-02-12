package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

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
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Orphaned Media File Cleanup Service Tests")
class OrphanedMediaFileCleanupServiceTest {

  private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
  private final MediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
  private final MovieRepository fakeMovieRepository = new FakeMovieRepository();
  private final MovieService movieService =
      new MovieService(
          fakeMovieRepository, null, null, null, null, null, event -> {}, mock(ImageService.class));
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

  private final OrphanedMediaFileCleanupService orphanedMediaFileCleanupService =
      new OrphanedMediaFileCleanupService(
          fakeLibraryRepository,
          fakeMediaFileRepository,
          movieService,
          fileSystem,
          noOpTransactionTemplate());

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
                .filepathUri("/library/nonexistent/movie.mkv")
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
                .filepathUri(FilepathCodec.encode(moviePath))
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
                .filepathUri("/other/library/nonexistent.mkv")
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
            .filepathUri("/library/nonexistent/gone-movie.mkv")
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
            .filepathUri(FilepathCodec.encode(existingPath))
            .filename("Surviving Movie (2020).mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .filepathUri("/library/nonexistent/surviving-movie-copy.mkv")
            .filename("surviving-movie-copy.mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMovieRepository.findById(movie.getId())).isPresent();
  }

  @Test
  @DisplayName("Should clean up orphaned files when ScanCompletedEvent is received")
  void shouldCleanupOrphanedFilesWhenScanCompletedEventReceived() {
    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri("/library/nonexistent/movie.mkv")
            .filename("movie.mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    var event = new ScanCompletedEvent(library.getId());
    orphanedMediaFileCleanupService.onScanCompleted(event);

    var remainingFiles = fakeMediaFileRepository.findByLibraryId(library.getId());
    assertThat(remainingFiles).isEmpty();
  }

  @Test
  @DisplayName("Should handle gracefully when library was deleted before cleanup runs")
  void shouldHandleGracefullyWhenLibraryDeletedBeforeCleanup() {
    var event = new ScanCompletedEvent(UUID.randomUUID());

    assertThatNoException()
        .isThrownBy(() -> orphanedMediaFileCleanupService.onScanCompleted(event));
  }

  @Test
  @DisplayName(
      "Should treat media file as orphaned when filepathUri contains unmappable characters")
  void shouldTreatMediaFileAsOrphanedWhenFilepathContainsUnmappableCharacters() {
    var corruptedFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("/library/movie\u0000corrupted.mkv")
                .filename("corrupted.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMediaFileRepository.findById(corruptedFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should still clean up valid orphaned files when one file has unmappable path")
  void shouldStillCleanUpValidOrphanedFilesWhenOneFileHasUnmappablePath() {
    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri("/library/movie\u0000corrupted.mkv")
            .filename("corrupted.mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    var validOrphan =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("/library/nonexistent/valid-orphan.mkv")
                .filename("valid-orphan.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    assertThat(fakeMediaFileRepository.findById(validOrphan.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should not propagate unexpected exception from cleanup")
  void shouldNotPropagateUnexpectedExceptionFromCleanup() {
    var throwingMediaFileRepository =
        new FakeMediaFileRepository() {
          @Override
          public java.util.List<MediaFile> findByLibraryId(UUID libraryId) {
            throw new RuntimeException("Simulated unexpected failure");
          }
        };

    var serviceWithThrowingRepo =
        new OrphanedMediaFileCleanupService(
            fakeLibraryRepository,
            throwingMediaFileRepository,
            movieService,
            fileSystem,
            noOpTransactionTemplate());

    var event = new ScanCompletedEvent(library.getId());
    serviceWithThrowingRepo.onScanCompleted(event);
  }

  private Path createMovieFile(String folder, String filename) throws IOException {
    var rootPath = fileSystem.getPath(library.getFilepath());
    var movieFolder = rootPath.resolve(folder);
    Files.createDirectory(movieFolder);
    var movieFile = movieFolder.resolve(filename);
    Files.createFile(movieFile);
    return movieFile;
  }

  private static TransactionTemplate noOpTransactionTemplate() {
    return new TransactionTemplate(
        new AbstractPlatformTransactionManager() {
          @Override
          protected Object doGetTransaction() {
            return new Object();
          }

          @Override
          protected void doBegin(Object transaction, TransactionDefinition definition) {}

          @Override
          protected void doCommit(DefaultTransactionStatus status) {}

          @Override
          protected void doRollback(DefaultTransactionStatus status) {}
        });
  }
}
