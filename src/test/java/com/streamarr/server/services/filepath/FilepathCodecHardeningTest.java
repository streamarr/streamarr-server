package com.streamarr.server.services.filepath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

@Tag("UnitTest")
@DisplayName("Filepath Codec Hardening Tests")
class FilepathCodecHardeningTest {

  @ParameterizedTest(name = "{0}: {1}")
  @CsvFileSource(resources = "/filepath-codec/valid-utf8-boundaries.csv", delimiter = '|')
  @DisplayName("Should decode valid UTF-8 boundary when reading filepath URI text")
  void shouldDecodeValidUtf8BoundaryWhenReadingFilepathUriText(
      String caseName, String encodedBytes, String expectedCodePointHex) {
    var filename = FilepathCodec.filenameOf("file:///media/" + encodedBytes + ".mkv");
    var expectedCodePoint = Integer.parseInt(expectedCodePointHex, 16);

    assertThat(filename.codePointAt(0)).as(caseName).isEqualTo(expectedCodePoint);
    assertThat(filename.substring(Character.charCount(expectedCodePoint))).isEqualTo(".mkv");
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvFileSource(resources = "/filepath-codec/malformed-utf8.csv", delimiter = '|')
  @DisplayName("Should reject malformed UTF-8 bytes when reading filepath URI text")
  void shouldRejectMalformedUtf8BytesWhenReadingFilepathUriText(
      String caseName, String encodedBytes) {
    var filepathUri = "file:///media/" + encodedBytes + ".mkv";

    assertThatThrownBy(() -> FilepathCodec.filenameOf(filepathUri))
        .as(caseName)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(filepathUri);
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvFileSource(resources = "/filepath-codec/malformed-percent-escapes.csv", delimiter = '|')
  @DisplayName(
      "Should reject malformed percent escape without hanging when reading filepath URI text")
  void shouldRejectMalformedPercentEscapeWithoutHangingWhenReadingFilepathUriText(
      String caseName, String encodedPath) {
    var filepathUri = "file:///media/" + encodedPath;

    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () ->
            assertThatThrownBy(() -> FilepathCodec.filenameOf(filepathUri))
                .as(caseName)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(filepathUri));
  }
}
