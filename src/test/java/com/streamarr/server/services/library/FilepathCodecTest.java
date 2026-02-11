package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Filepath Codec Tests")
class FilepathCodecTest {

  @Test
  @DisplayName("Should encode absolute path to file URI")
  void shouldEncodeAbsolutePathToFileUri() {
    var encoded = FilepathCodec.encode(Path.of("/media/movie.mkv"));

    assertThat(encoded).isEqualTo("file:///media/movie.mkv");
  }

  @Test
  @DisplayName("Should decode file URI back to original path")
  void shouldDecodeFileUriBackToOriginalPath() {
    var path = FilepathCodec.decode("file:///media/movie.mkv");

    assertThat(path).isEqualTo(Path.of("/media/movie.mkv"));
  }

  @Test
  @DisplayName("Should roundtrip preserve non-ASCII characters")
  void shouldRoundtripPreserveNonAsciiCharacters() throws IOException {
    try (FileSystem jimfs = Jimfs.newFileSystem(Configuration.unix())) {
      var dir = jimfs.getPath("/media");
      Files.createDirectories(dir);
      var originalPath = dir.resolve("Alien\u00B3 (1992).mkv");
      Files.createFile(originalPath);

      var encoded = FilepathCodec.encode(originalPath);
      var decoded = FilepathCodec.decode(jimfs, encoded);

      assertThat(decoded).isEqualTo(originalPath);
    }
  }

  @Test
  @DisplayName("Should fall back to plain path when no URI scheme present")
  void shouldFallBackToPlainPathWhenNoUriScheme() throws IOException {
    try (FileSystem jimfs = Jimfs.newFileSystem(Configuration.unix())) {
      var path = FilepathCodec.decode(jimfs, "/plain/path");

      assertThat(path).isEqualTo(jimfs.getPath("/plain/path"));
    }
  }
}
