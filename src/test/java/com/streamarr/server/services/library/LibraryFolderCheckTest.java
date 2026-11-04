package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.RemoteTranscodeProperties;
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Library folder check tests")
class LibraryFolderCheckTest {

  private static final ExpectedFailure SOFT_LINK_FAILURE =
      new ExpectedFailure(LibraryFolderProblem.SOFT_LINK, "soft_link");

  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LibraryFolderCheck libraryFolderCheck =
      LibraryFolderCheck.builder()
          .fileSystem(fileSystem)
          .mediaFileRepository(mediaFiles)
          .meterRegistry(meterRegistry)
          .mediaRoot(mediaRootAt(fileSystem, "/media"))
          .build();

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
        LibraryFolderCheck.builder()
            .fileSystem(
                ThrowingFileSystemWrapper.failingBeforeTheFirstEntry(
                    fileSystem,
                    () -> new DirectoryIteratorException(new IOException("Simulated I/O error"))))
            .mediaFileRepository(mediaFiles)
            .meterRegistry(meterRegistry)
            .mediaRoot(mediaRootAt(fileSystem, "/media"))
            .build();

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

  // Jimfs diverges from the JDK's link semantics, so these tests use the real file system.
  @Test
  @DisplayName(
      "Should fail as a soft link when a folder between the media root and the library folder is a"
          + " soft link")
  void shouldFailAsASoftLinkWhenAFolderBetweenTheMediaRootAndTheLibraryFolderIsASoftLink(
      @TempDir Path temp) throws IOException {
    var mediaRoot = Files.createDirectory(temp.resolve("media"));
    var realShows = Files.createDirectories(temp.resolve("real-shows/Movies"));
    Files.createFile(realShows.resolve("movie.mkv"));
    var linkedShows = Files.createSymbolicLink(mediaRoot.resolve("shows"), realShows.getParent());
    var library = libraryAt(linkedShows.resolve("Movies"));

    assertFailure(checkWithMediaRoot(mediaRoot), library, SOFT_LINK_FAILURE);
  }

  @Test
  @DisplayName("Should fail as a soft link when the library folder is a soft link")
  void shouldFailAsASoftLinkWhenTheLibraryFolderIsASoftLink(@TempDir Path temp) throws IOException {
    var mediaRoot = Files.createDirectory(temp.resolve("media"));
    var realMovies = Files.createDirectory(temp.resolve("real-movies"));
    Files.createFile(realMovies.resolve("movie.mkv"));
    var library = libraryAt(Files.createSymbolicLink(mediaRoot.resolve("Movies"), realMovies));

    assertFailure(checkWithMediaRoot(mediaRoot), library, SOFT_LINK_FAILURE);
  }

  @Test
  @DisplayName("Should pass when only the way to the media root passes a soft link")
  void shouldPassWhenOnlyTheWayToTheMediaRootPassesASoftLink(@TempDir Path temp)
      throws IOException {
    var realMediaRoot = Files.createDirectory(temp.resolve("real-media"));
    Files.createFile(Files.createDirectory(realMediaRoot.resolve("Movies")).resolve("movie.mkv"));
    var mediaRoot = Files.createSymbolicLink(temp.resolve("media"), realMediaRoot);
    var library = libraryAt(mediaRoot.resolve("Movies"));

    assertThatNoException().isThrownBy(() -> checkWithMediaRoot(mediaRoot).requirePassed(library));
  }

  private LibraryFolderCheck checkWithMediaRoot(Path mediaRoot) {
    return LibraryFolderCheck.builder()
        .fileSystem(FileSystems.getDefault())
        .mediaFileRepository(mediaFiles)
        .meterRegistry(meterRegistry)
        .mediaRoot(mediaRootAt(FileSystems.getDefault(), mediaRoot.toString()))
        .build();
  }

  private static MediaRoot mediaRootAt(FileSystem mediaFileSystem, String mediaRoot) {
    return new MediaRoot(
        mediaFileSystem, new RemoteTranscodeProperties(UUID.randomUUID(), mediaRoot));
  }

  private void assertFailure(Library library, LibraryFolderProblem problem, String reasonTag) {
    assertFailure(libraryFolderCheck, library, new ExpectedFailure(problem, reasonTag));
  }

  private void assertFailure(
      LibraryFolderCheck check, Library library, ExpectedFailure expectedFailure) {
    assertThatThrownBy(() -> check.requirePassed(library))
        .isInstanceOfSatisfying(
            LibraryFolderCheckFailedException.class,
            failure -> assertThat(failure.getProblem()).isEqualTo(expectedFailure.problem()));
    assertThat(
            meterRegistry
                .get(LibraryFolderCheck.FAILURES_METRIC)
                .tag("reason", expectedFailure.reasonTag())
                .counter()
                .count())
        .isEqualTo(1);
  }

  private record ExpectedFailure(LibraryFolderProblem problem, String reasonTag) {}

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
