package com.streamarr.server.services.library.walk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Throwaway benchmark lever: the parallel walk streams the serial walk's files. */
@Tag("UnitTest")
@DisplayName("POC parallel library walk tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ParallelLibraryWalkTest {

  private final IgnoredFileValidator ignored =
      new IgnoredFileValidator(new LibraryScanProperties(null, null, null));
  private final ParallelLibraryWalk parallel = new ParallelLibraryWalk(ignored);

  @TempDir Path root;

  private Set<Path> scanFilter(Stream<Path> entries) {
    return entries
        .filter(Files::isRegularFile)
        .filter(file -> !ignored.shouldIgnore(file))
        .collect(Collectors.toSet());
  }

  private Set<Path> serial(Path start) throws IOException {
    try (var entries = new SerialLibraryWalk().walk(start)) {
      return scanFilter(entries);
    }
  }

  private Set<Path> parallelRaw(Path start) throws IOException {
    try (var entries = parallel.walk(start)) {
      return entries.collect(Collectors.toSet());
    }
  }

  private static void file(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    Files.createFile(path);
  }

  private void buildLibrary() throws IOException {
    file(root.resolve("Movie A (2001)/Movie A (2001).mkv"));
    file(root.resolve("Movie A (2001)/Movie A (2001).en.srt"));
    file(root.resolve("Movie A (2001)/.DS_Store"));
    file(root.resolve("Movie A (2001)/._Movie A (2001).mkv"));
    file(root.resolve("Nested/Deeper/Deepest/deep.mp4"));
    file(root.resolve("Nested/Deeper/notes.txt"));
    file(root.resolve("Disc (1999)/disc.iso"));
    file(root.resolve("top-level.mkv"));
    Files.createDirectories(root.resolve("Empty"));
    Files.createSymbolicLink(
        root.resolve("link-to-file.mkv"), root.resolve("Movie A (2001)/Movie A (2001).mkv"));
    Files.createSymbolicLink(root.resolve("link-to-dir"), root.resolve("Nested"));
    for (var i = 0; i < 300; i++) {
      file(root.resolve("Bulk " + i + "/Bulk " + i + ".mkv"));
      file(root.resolve("Bulk " + i + "/Bulk " + i + ".srt"));
    }
  }

  @Test
  @DisplayName(
      "Should stream the serial walk's scan files when the library has nested, ignored and linked entries")
  void shouldStreamSerialWalkFilesWhenLibraryHasNestedIgnoredAndLinkedEntries() throws IOException {
    buildLibrary();

    var expected = serial(root);
    var raw = parallelRaw(root);
    Set<Path> filtered;
    try (var entries = parallel.walk(root)) {
      filtered = scanFilter(entries);
    }

    assertThat(expected)
        .hasSize(305)
        .contains(root.resolve("link-to-file.mkv"), root.resolve("Disc (1999)/disc.iso"))
        .doesNotContain(root.resolve("link-to-dir/Deeper/Deepest/deep.mp4"));
    assertThat(raw).isEqualTo(expected);
    assertThat(filtered).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should stream the root itself when the root is a regular file")
  void shouldStreamRootWhenRootIsRegularFile() throws IOException {
    var single = root.resolve("single.mkv");
    Files.createFile(single);

    try (var entries = parallel.walk(single)) {
      assertThat(scanFilter(entries)).isEqualTo(serial(single)).containsExactly(single);
    }
  }

  @Test
  @DisplayName("Should throw like the serial walk when the root does not exist")
  void shouldThrowWhenRootDoesNotExist() {
    var missing = root.resolve("missing");

    assertThatThrownBy(() -> new SerialLibraryWalk().walk(missing))
        .isInstanceOf(NoSuchFileException.class);
    assertThatThrownBy(() -> parallel.walk(missing)).isInstanceOf(NoSuchFileException.class);
  }

  @Test
  @DisplayName(
      "Should end the stream with an unchecked I/O failure when a directory cannot be read")
  void shouldEndStreamWithUncheckedFailureWhenDirectoryCannotBeRead() throws IOException {
    buildLibrary();
    var locked = root.resolve("Locked");
    file(locked.resolve("hidden.mkv"));
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));

    try {
      assertThatThrownBy(() -> serial(root))
          .isInstanceOf(UncheckedIOException.class)
          .hasCauseInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> parallelRaw(root))
          .isInstanceOf(UncheckedIOException.class)
          .hasCauseInstanceOf(AccessDeniedException.class);
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should return promptly and stream again when a stream is closed after a few files")
  void shouldReturnPromptlyWhenStreamIsClosedAfterFewFiles() throws IOException {
    buildLibrary();

    try (var entries = parallel.walk(root)) {
      assertThat(entries.limit(3).toList()).hasSize(3);
    }

    assertThat(parallelRaw(root)).isEqualTo(serial(root));
  }
}
