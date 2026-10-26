package com.streamarr.server.services.filepath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.fakes.SecurityExceptionFileSystem;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Filepath Name Normalizer Tests")
class FilepathNameNormalizerTest {

  @Test
  @DisplayName("Should keep the given name when another name in the folder has the same file key")
  void shouldKeepGivenNameWhenAnotherNameInFolderHasSameFileKey() throws IOException {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
      var feature =
          Files.createFile(Files.createDirectory(fileSystem.getPath("/media")).resolve("b"));
      Files.createLink(fileSystem.getPath("/media/a"), feature);
      Files.createLink(fileSystem.getPath("/media/c"), feature);

      assertThat(FilepathNameNormalizer.normalizeToFileSystemNames(feature)).isEqualTo(feature);
    }
  }

  @Test
  @DisplayName(
      "Should fail when several names in the folder have the file key and none is the given name")
  void shouldFailWhenSeveralNamesInFolderHaveFileKeyAndNoneIsGivenName() throws IOException {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.osX())) {
      var feature =
          Files.createFile(Files.createDirectory(fileSystem.getPath("/media")).resolve("Feature"));
      Files.createLink(fileSystem.getPath("/media/Trailer"), feature);

      assertThatThrownBy(
              () ->
                  FilepathNameNormalizer.normalizeToFileSystemNames(
                      fileSystem.getPath("/media/FEATURE")))
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  @DisplayName("Should fail when the stored name's attributes cannot be read")
  void shouldFailWhenStoredNamesAttributesCannotBeRead() throws IOException {
    try (var jimfs = Jimfs.newFileSystem(Configuration.osX())) {
      Files.createDirectories(jimfs.getPath("/media/Movies"));
      var fileSystem = SecurityExceptionFileSystem.failingAttributesOf(jimfs, "Movies");

      assertThatThrownBy(
              () ->
                  FilepathNameNormalizer.normalizeToFileSystemNames(
                      fileSystem.getPath("/media/movies")))
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  @DisplayName("Should fail with an IOException when listing a folder fails partway")
  void shouldFailWithIOExceptionWhenListingFolderFailsPartway() throws IOException {
    try (var jimfs = Jimfs.newFileSystem(Configuration.unix())) {
      Files.createDirectories(jimfs.getPath("/media/movies"));
      var fileSystem =
          ThrowingFileSystemWrapper.failingBeforeTheFirstEntry(
              jimfs, () -> new DirectoryIteratorException(new IOException("mount went away")));

      assertThatThrownBy(
              () ->
                  FilepathNameNormalizer.normalizeToFileSystemNames(
                      fileSystem.getPath("/media/movies")))
          .isInstanceOf(IOException.class)
          .hasMessage("mount went away");
    }
  }
}
