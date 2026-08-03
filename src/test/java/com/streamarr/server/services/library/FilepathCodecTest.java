package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  @DisplayName("Should encode to file URI when given absolute path")
  void shouldEncodeToFileUriWhenGivenAbsolutePath() {
    var encoded = FilepathCodec.encode(Path.of("/media/movie.mkv"));

    assertThat(encoded).isEqualTo("file:///media/movie.mkv");
  }

  @Test
  @DisplayName("Should decode back to original path when given file URI")
  void shouldDecodeBackToOriginalPathWhenGivenFileUri() {
    var path = FilepathCodec.decode("file:///media/movie.mkv");

    assertThat(path).isEqualTo(Path.of("/media/movie.mkv"));
  }

  @Test
  @DisplayName("Should preserve non-ASCII characters when roundtripping through codec")
  void shouldPreserveNonAsciiCharactersWhenRoundtrippingThroughCodec() throws IOException {
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
  @DisplayName("Should produce percent-encoded URI when encoding path with spaces")
  void shouldProducePercentEncodedUriWhenEncodingPathWithSpaces() {
    var encoded = FilepathCodec.encode(Path.of("/media/My Movies"));

    assertThat(encoded).isEqualTo("file:///media/My%20Movies");
  }

  @Test
  @DisplayName("Should decode file:// URI against Jimfs filesystem")
  void shouldDecodeFileUriAgainstJimfsFilesystem() throws IOException {
    try (FileSystem jimfs = Jimfs.newFileSystem(Configuration.unix())) {
      var decoded = FilepathCodec.decode(jimfs, "file:///some/path");

      assertThat(decoded.getFileSystem()).isSameAs(jimfs);
      assertThat(decoded).isEqualTo(jimfs.getPath("/some/path"));
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

  @Test
  @DisplayName("Should decode filename as UTF-8 when platform charset would mangle the path")
  void shouldDecodeFilenameAsUtf8WhenPlatformCharsetWouldMangleThePath() {
    var filenameOnDisk = "Déjà Vu (2006) - [BLURAY-1080p][DTS 5.1].mkv";
    var whatPathToStringYieldsUnderAnAsciiLocale =
        new String(filenameOnDisk.getBytes(StandardCharsets.UTF_8), StandardCharsets.US_ASCII);

    var filenameFromUri =
        FilepathCodec.filenameOf(
            "file:///mpool/media/movies/"
                + "D%C3%A9j%C3%A0%20Vu%20(2006)%20-%20%5BBLURAY-1080p%5D%5BDTS%205.1%5D.mkv");

    assertThat(filenameFromUri)
        .isEqualTo(filenameOnDisk)
        .isNotEqualTo(whatPathToStringYieldsUnderAnAsciiLocale);
  }

  @Test
  @DisplayName("Should extract filename when given URI encoded from a non-ASCII path")
  void shouldExtractFilenameWhenGivenUriEncodedFromNonAsciiPath() throws IOException {
    try (FileSystem jimfs = Jimfs.newFileSystem(Configuration.unix())) {
      var path = jimfs.getPath("/media/movies/Alien³ (1992).mkv");

      assertThat(FilepathCodec.filenameOf(FilepathCodec.encode(path)))
          .isEqualTo("Alien³ (1992).mkv");
    }
  }

  @Test
  @DisplayName("Should extract final segment when URI denotes a directory with a trailing slash")
  void shouldExtractFinalSegmentWhenUriDenotesDirectoryWithTrailingSlash() {
    assertThat(FilepathCodec.filenameOf("file:///media/Am%C3%A9lie%20(2001)/"))
        .isEqualTo("Amélie (2001)");
  }

  @Test
  @DisplayName("Should extract final segment when given a plain path without a URI scheme")
  void shouldExtractFinalSegmentWhenGivenPlainPathWithoutUriScheme() {
    assertThat(FilepathCodec.filenameOf("/media/movies/movie.mkv")).isEqualTo("movie.mkv");
  }

  @Test
  @DisplayName("Should reject filepath URI when it has no final segment")
  void shouldRejectFilepathUriWhenItHasNoFinalSegment() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:///"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///");
  }
}
