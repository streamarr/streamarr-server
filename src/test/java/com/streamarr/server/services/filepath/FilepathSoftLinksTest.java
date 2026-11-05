package com.streamarr.server.services.filepath;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Filepath soft links tests")
class FilepathSoftLinksTest {

  @Test
  @DisplayName("Should fail when a folder on the path cannot be searched")
  void shouldFailWhenAFolderOnThePathCannotBeSearched(@TempDir Path temp) throws IOException {
    var locked = Files.createDirectory(temp.resolve("locked"));
    var file = Files.createFile(locked.resolve("movie.mkv"));
    Files.setPosixFilePermissions(locked, Set.of());
    try {
      assumeFalse(Files.isReadable(locked), "folder permissions apply to the test user");

      assertThatThrownBy(() -> FilepathSoftLinks.hasSoftLinkFrom(temp, file))
          .isInstanceOf(IOException.class);
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
