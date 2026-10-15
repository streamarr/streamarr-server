package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.LibraryOperationDriver;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Library folder check integration tests")
class LibraryFolderCheckIT extends AbstractIntegrationTest {

  private static final String FAILURES_METRIC = "streamarr.library.folder_check.failures";

  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MeterRegistry meterRegistry;

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should remove nothing and end unhealthy when the library folder is emptied")
  void shouldRemoveNothingAndEndUnhealthyWhenTheLibraryFolderIsEmptied() throws IOException {
    var libraryPath = Files.createDirectory(tempDir.resolve("library"));
    var library = saveLibrary(libraryPath);
    var movieFile = Files.createFile(libraryPath.resolve("movie.mkv"));
    var movie = saveMovie(library);
    var mediaFile = saveMediaFile(library, movie, movieFile);
    var failuresBefore = failureCount("empty");
    Files.delete(movieFile);

    libraryOperationDriver.scanToEnd(library.getId());

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isPresent();
    assertThat(movieRepository.findById(movie.getId())).isPresent();
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(failureCount("empty")).isEqualTo(failuresBefore + 1);
  }

  @Test
  @DisplayName("Should remove nothing and end unhealthy when the library folder is deleted")
  void shouldRemoveNothingAndEndUnhealthyWhenTheLibraryFolderIsDeleted() throws IOException {
    var libraryPath = Files.createDirectory(tempDir.resolve("library"));
    var library = saveLibrary(libraryPath);
    var movie = saveMovie(library);
    var mediaFile =
        saveMediaFile(library, movie, Files.createFile(libraryPath.resolve("movie.mkv")));
    var failuresBefore = failureCount("missing");
    FileUtils.deleteDirectory(libraryPath.toFile());

    libraryOperationDriver.scanToEnd(library.getId());

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isPresent();
    assertThat(movieRepository.findById(movie.getId())).isPresent();
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(failureCount("missing")).isEqualTo(failuresBefore + 1);
  }

  @Test
  @DisplayName("Should remove nothing and end unhealthy when the library folder cannot be read")
  void shouldRemoveNothingAndEndUnhealthyWhenTheLibraryFolderCannotBeRead() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    var libraryPath = Files.createDirectory(tempDir.resolve("library"));
    var library = saveLibrary(libraryPath);
    var movie = saveMovie(library);
    var mediaFile =
        saveMediaFile(library, movie, Files.createFile(libraryPath.resolve("movie.mkv")));
    var failuresBefore = failureCount("unreadable");
    var readablePermissions = Files.getPosixFilePermissions(libraryPath);
    Files.setPosixFilePermissions(libraryPath, PosixFilePermissions.fromString("---------"));

    try {
      assumeFalse(Files.isReadable(libraryPath), "This user reads folders without permission");
      libraryOperationDriver.scanToEnd(library.getId());
    } finally {
      Files.setPosixFilePermissions(libraryPath, readablePermissions);
    }

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isPresent();
    assertThat(movieRepository.findById(movie.getId())).isPresent();
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(failureCount("unreadable")).isEqualTo(failuresBefore + 1);
  }

  @Test
  @DisplayName("Should stay healthy when a library with no file records has an empty folder")
  void shouldStayHealthyWhenALibraryWithNoFileRecordsHasAnEmptyFolder() throws IOException {
    var library = saveLibrary(Files.createDirectory(tempDir.resolve("library")));

    libraryOperationDriver.scanToEnd(library.getId());

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should remove a deleted file when the library folder still holds other entries")
  void shouldRemoveADeletedFileWhenTheLibraryFolderStillHoldsOtherEntries() throws IOException {
    var libraryPath = Files.createDirectory(tempDir.resolve("library"));
    var library = saveLibrary(libraryPath);
    var movie = saveMovie(library);
    var deletedFile =
        saveMediaFile(library, movie, Files.createFile(libraryPath.resolve("deleted.mkv")));
    Files.createFile(libraryPath.resolve("notes.txt"));
    Files.delete(libraryPath.resolve("deleted.mkv"));

    libraryOperationDriver.scanToEnd(library.getId());

    assertThat(mediaFileRepository.findById(deletedFile.getId())).isEmpty();
    assertThat(movieRepository.findById(movie.getId())).isEmpty();
    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  private Library saveLibrary(Path libraryPath) {
    return libraryRepository.saveAndFlush(
        LibraryFixtureCreator.buildFakeLibrary().toBuilder()
            .filepathUri(FilepathCodec.encode(libraryPath))
            .build());
  }

  private Movie saveMovie(Library library) {
    return movieRepository.saveAndFlush(Movie.builder().title("Movie").library(library).build());
  }

  private MediaFile saveMediaFile(Library library, Movie movie, Path path) {
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .filepathUri(FilepathCodec.encode(path))
            .filename(path.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .build());
  }

  private LibraryStatus statusOf(Library library) {
    return libraryRepository.findById(library.getId()).orElseThrow().getStatus();
  }

  private double failureCount(String reason) {
    var counter = meterRegistry.find(FAILURES_METRIC).tag("reason", reason).counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }
}
