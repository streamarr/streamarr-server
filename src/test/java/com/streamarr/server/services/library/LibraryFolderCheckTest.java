package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryFolderProblem;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.exceptions.LibraryFolderCheckFailedException;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.filepath.FilepathCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library folder check tests")
class LibraryFolderCheckTest {

  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LibraryFolderCheck libraryFolderCheck =
      new LibraryFolderCheck(fileSystem, mediaFiles, meterRegistry);

  @AfterEach
  void closeFileSystem() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should pass when the library folder holds an entry")
  void shouldPassWhenTheLibraryFolderHoldsAnEntry() throws IOException {
    var folder = Files.createDirectories(fileSystem.getPath("/library"));
    Files.createFile(folder.resolve("movie.mkv"));
    var library = libraryAt(folder);
    saveFileRecord(library, folder.resolve("movie.mkv"));

    assertThatNoException().isThrownBy(() -> libraryFolderCheck.requirePassed(library));
    assertThat(meterRegistry.find(LibraryFolderCheck.FAILURES_METRIC).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should pass when the library folder is empty and the library has no file records")
  void shouldPassWhenTheLibraryFolderIsEmptyAndTheLibraryHasNoFileRecords() throws IOException {
    var library = libraryAt(Files.createDirectories(fileSystem.getPath("/library")));

    assertThatNoException().isThrownBy(() -> libraryFolderCheck.requirePassed(library));
    assertThat(meterRegistry.find(LibraryFolderCheck.FAILURES_METRIC).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should fail as empty when the library folder is empty and the library has records")
  void shouldFailAsEmptyWhenTheLibraryFolderIsEmptyAndTheLibraryHasRecords() throws IOException {
    var folder = Files.createDirectories(fileSystem.getPath("/library"));
    var library = libraryAt(folder);
    saveFileRecord(library, folder.resolve("movie.mkv"));

    assertFailure(library, LibraryFolderProblem.EMPTY, "empty");
  }

  @Test
  @DisplayName("Should ignore file records of other libraries when the library folder is empty")
  void shouldIgnoreFileRecordsOfOtherLibrariesWhenTheLibraryFolderIsEmpty() throws IOException {
    var library = libraryAt(Files.createDirectories(fileSystem.getPath("/library")));
    var otherLibrary = libraryAt(Files.createDirectories(fileSystem.getPath("/other")));
    saveFileRecord(otherLibrary, fileSystem.getPath("/other/movie.mkv"));

    assertThatNoException().isThrownBy(() -> libraryFolderCheck.requirePassed(library));
  }

  @Test
  @DisplayName("Should fail as missing when the library folder does not exist")
  void shouldFailAsMissingWhenTheLibraryFolderDoesNotExist() {
    var library = libraryAt(fileSystem.getPath("/library"));

    assertFailure(library, LibraryFolderProblem.MISSING, "missing");
  }

  @Test
  @DisplayName("Should fail as missing when a regular file stands at the library folder's path")
  void shouldFailAsMissingWhenARegularFileStandsAtTheLibraryFolderPath() throws IOException {
    var library = libraryAt(Files.createFile(fileSystem.getPath("/library")));

    assertFailure(library, LibraryFolderProblem.MISSING, "missing");
  }

  @Test
  @DisplayName("Should fail as unreadable when reading the library folder's entries fails")
  void shouldFailAsUnreadableWhenReadingTheLibraryFolderEntriesFails() throws IOException {
    var folder = Files.createDirectories(fileSystem.getPath("/library"));
    Files.createFile(folder.resolve("movie.mkv"));
    var library =
        LibraryFixtureCreator.buildFakeLibrary().toBuilder()
            .id(UUID.randomUUID())
            .filepathUri("file:///library")
            .build();
    var failingCheck =
        new LibraryFolderCheck(
            ThrowingFileSystemWrapper.failingBeforeTheFirstEntry(
                fileSystem,
                () -> new DirectoryIteratorException(new IOException("Simulated I/O error"))),
            mediaFiles,
            meterRegistry);

    assertThatThrownBy(() -> failingCheck.requirePassed(library))
        .isInstanceOfSatisfying(
            LibraryFolderCheckFailedException.class,
            failure -> assertThat(failure.getProblem()).isEqualTo(LibraryFolderProblem.UNREADABLE));
  }

  @Test
  @DisplayName("Should fail as unreadable when the library folder's stored path cannot be decoded")
  void shouldFailAsUnreadableWhenTheLibraryFolderStoredPathCannotBeDecoded() {
    var library =
        LibraryFixtureCreator.buildFakeLibrary().toBuilder()
            .id(UUID.randomUUID())
            .filepathUri("file:///library%00corrupted")
            .build();

    assertFailure(library, LibraryFolderProblem.UNREADABLE, "unreadable");
  }

  private void assertFailure(Library library, LibraryFolderProblem problem, String reasonTag) {
    assertThatThrownBy(() -> libraryFolderCheck.requirePassed(library))
        .isInstanceOfSatisfying(
            LibraryFolderCheckFailedException.class,
            failure -> assertThat(failure.getProblem()).isEqualTo(problem));
    assertThat(
            meterRegistry
                .get(LibraryFolderCheck.FAILURES_METRIC)
                .tag("reason", reasonTag)
                .counter()
                .count())
        .isEqualTo(1);
  }

  private static Library libraryAt(Path folder) {
    return LibraryFixtureCreator.buildFakeLibrary().toBuilder()
        .id(UUID.randomUUID())
        .filepathUri(FilepathCodec.encode(folder))
        .build();
  }

  private void saveFileRecord(Library library, Path path) {
    mediaFiles.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri(FilepathCodec.encode(path))
            .filename(path.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .build());
  }
}
