package com.streamarr.server.services.filepath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  @DisplayName("Should decode the containing directory name when given a filepath URI")
  void shouldDecodeContainingDirectoryNameWhenGivenFilepathUri() {
    assertThat(
            FilepathCodec.parentNameOf(
                "file:///media/movies/D%C3%A9j%C3%A0%20Vu%20(2006)/movie.mkv"))
        .contains("Déjà Vu (2006)");
  }

  @Test
  @DisplayName("Should decode the grandparent directory name when given a filepath URI")
  void shouldDecodeGrandparentDirectoryNameWhenGivenFilepathUri() {
    assertThat(
            FilepathCodec.grandparentNameOf(
                "file:///media/Am%C3%A9lie%20(2001)/S%C3%A6son%203/episode.mkv"))
        .contains("Amélie (2001)");
  }

  @Test
  @DisplayName("Should return no parent name when the file sits at the filesystem root")
  void shouldReturnNoParentNameWhenFileSitsAtFilesystemRoot() {
    assertThat(FilepathCodec.parentNameOf("file:///movie.mkv")).isEmpty();
  }

  @Test
  @DisplayName("Should return no grandparent name when nothing sits two directories above")
  void shouldReturnNoGrandparentNameWhenNothingSitsTwoDirectoriesAbove() {
    assertThat(FilepathCodec.grandparentNameOf("file:///media/movie.mkv")).isEmpty();
  }

  @Test
  @DisplayName("Should ignore a trailing slash when naming the containing directory")
  void shouldIgnoreTrailingSlashWhenNamingContainingDirectory() {
    assertThat(FilepathCodec.parentNameOf("file:///media/movies/Am%C3%A9lie%20(2001)/"))
        .contains("movies");
  }

  @Test
  @DisplayName("Should name directories when given a plain path without a URI scheme")
  void shouldNameDirectoriesWhenGivenPlainPathWithoutUriScheme() {
    assertThat(FilepathCodec.parentNameOf("/media/movies/movie.mkv")).contains("movies");
    assertThat(FilepathCodec.grandparentNameOf("/media/movies/movie.mkv")).contains("media");
  }

  @Test
  @DisplayName("Should decode the whole path as UTF-8 when given a filepath URI")
  void shouldDecodeWholePathAsUtf8WhenGivenFilepathUri() {
    assertThat(FilepathCodec.pathOf("file:///media/D%C3%A9j%C3%A0%20Vu%20(2006)/movie.mkv"))
        .isEqualTo("/media/Déjà Vu (2006)/movie.mkv");
  }

  @Test
  @DisplayName("Should return the path unchanged when given a plain path without a URI scheme")
  void shouldReturnPathUnchangedWhenGivenPlainPathWithoutUriScheme() {
    assertThat(FilepathCodec.pathOf("/media/Déjà Vu (2006)/movie.mkv"))
        .isEqualTo("/media/Déjà Vu (2006)/movie.mkv");
  }

  @Test
  @DisplayName("Should reject filepath URI when it has no final segment")
  void shouldRejectFilepathUriWhenItHasNoFinalSegment() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:///"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///");
  }

  @Test
  @DisplayName("Should reject malformed file URI containing an unescaped space")
  void shouldRejectMalformedFileUriContainingUnescapedSpace() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:///media/My Movies/movie.mkv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///media/My Movies/movie.mkv");
  }

  @Test
  @DisplayName("Should reject filepath URI containing a query")
  void shouldRejectFilepathUriContainingQuery() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:///media/movie.mkv?download=true"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///media/movie.mkv?download=true");
  }

  @Test
  @DisplayName("Should reject filepath URI containing a fragment")
  void shouldRejectFilepathUriContainingFragment() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:///media/movie#1.mkv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///media/movie#1.mkv");
  }

  @Test
  @DisplayName("Should reject opaque file URI")
  void shouldRejectOpaqueFileUri() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:movie.mkv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:movie.mkv");
  }

  @Test
  @DisplayName("Should reject malformed file URI when decoding to a path")
  void shouldRejectMalformedFileUriWhenDecodingToPath() {
    assertThatThrownBy(() -> FilepathCodec.decode("file:///media/My Movies/movie.mkv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///media/My Movies/movie.mkv");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:///media/movie.mkv?download=true",
        "file:///media/movie#1.mkv",
        "file:movie.mkv"
      })
  @DisplayName("Should reject structurally invalid file URI when decoding to path")
  void shouldRejectStructurallyInvalidFileUriWhenDecodingToPath(String filepathUri) {
    assertThatThrownBy(() -> FilepathCodec.decode(filepathUri))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(filepathUri);
  }

  @Test
  @DisplayName("Should reject filepath URI whose percent-encoded bytes are not valid UTF-8")
  void shouldRejectFilepathUriWhosePercentEncodedBytesAreNotValidUtf8() {
    assertThatThrownBy(() -> FilepathCodec.filenameOf("file:///media/caf%E9.mkv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file:///media/caf%E9.mkv");
  }

  @Test
  @DisplayName("Should preserve non-UTF-8 bytes when decoding filepath URI to a path")
  void shouldPreserveNonUtf8BytesWhenDecodingFilepathUriToPath() {
    var filepathUri = "file:///media/caf%E9.mkv";

    var path = FilepathCodec.decode(filepathUri);

    assertThat(path).isEqualTo(Path.of(URI.create(filepathUri)));
  }

  @Test
  @DisplayName("Should roundtrip percent, plus, and hash characters through filepath URI")
  void shouldRoundtripPercentPlusAndHashCharactersThroughFilepathUri() {
    var filename = "100% Legit + Bonus #1.mkv";

    assertThat(FilepathCodec.filenameOf(FilepathCodec.encode(Path.of("/media", filename))))
        .isEqualTo(filename);
  }

  @Test
  @DisplayName("Should treat plain filename containing a colon as a legacy path")
  void shouldTreatPlainFilenameContainingColonAsLegacyPath() {
    assertThat(FilepathCodec.filenameOf("Frost:Nixon.mkv")).isEqualTo("Frost:Nixon.mkv");
  }
}
