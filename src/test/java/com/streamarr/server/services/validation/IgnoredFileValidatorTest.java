package com.streamarr.server.services.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.streamarr.server.config.LibraryScanProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Ignored File Validator Tests")
class IgnoredFileValidatorTest {

  private final IgnoredFileValidator validator =
      new IgnoredFileValidator(new LibraryScanProperties(null, null, null));

  @Test
  @DisplayName("Should ignore .DS_Store file")
  void shouldIgnoreWhenGivenDsStoreFile() {
    assertTrue(validator.shouldIgnore(Path.of(".DS_Store")));
  }

  @Test
  @DisplayName("Should ignore Thumbs.db file")
  void shouldIgnoreWhenGivenThumbsDbFile() {
    assertTrue(validator.shouldIgnore(Path.of("Thumbs.db")));
  }

  @Test
  @DisplayName("Should ignore desktop.ini file")
  void shouldIgnoreWhenGivenDesktopIniFile() {
    assertTrue(validator.shouldIgnore(Path.of("desktop.ini")));
  }

  @Test
  @DisplayName("Should ignore macOS resource fork file with ._ prefix")
  void shouldIgnoreWhenGivenMacResourceForkFile() {
    assertTrue(validator.shouldIgnore(Path.of("._movie.mkv")));
  }

  @Test
  @DisplayName("Should ignore .nfo metadata file")
  void shouldIgnoreWhenGivenNfoExtension() {
    assertTrue(validator.shouldIgnore(Path.of("movie.nfo")));
  }

  @Test
  @DisplayName("Should ignore .jpg image file")
  void shouldIgnoreWhenGivenImageExtension() {
    assertTrue(validator.shouldIgnore(Path.of("poster.jpg")));
  }

  @Test
  @DisplayName("Should ignore .tmp temporary file")
  void shouldIgnoreWhenGivenTmpExtension() {
    assertTrue(validator.shouldIgnore(Path.of("download.tmp")));
  }

  @Test
  @DisplayName("Should ignore .part partial download file")
  void shouldIgnoreWhenGivenPartExtension() {
    assertTrue(validator.shouldIgnore(Path.of("movie.mkv.part")));
  }

  @Test
  @DisplayName("Should not ignore .mkv video file")
  void shouldNotIgnoreWhenGivenMkvFile() {
    assertFalse(validator.shouldIgnore(Path.of("About Time (2013).mkv")));
  }

  @Test
  @DisplayName("Should not ignore .mp4 video file")
  void shouldNotIgnoreWhenGivenMp4File() {
    assertFalse(validator.shouldIgnore(Path.of("movie.mp4")));
  }

  @Test
  @DisplayName("Should ignore file extension case-insensitively")
  void shouldIgnoreExtensionCaseInsensitively() {
    assertTrue(validator.shouldIgnore(Path.of("poster.JPG")));
  }

  @Test
  @DisplayName("Should ignore filename case-insensitively")
  void shouldIgnoreFilenameCaseInsensitively() {
    assertTrue(validator.shouldIgnore(Path.of("THUMBS.DB")));
  }

  @Test
  @DisplayName("Should ignore when given additional configured filename")
  void shouldIgnoreWhenGivenAdditionalConfiguredFilename() {
    var customValidator =
        new IgnoredFileValidator(
            new LibraryScanProperties(List.of(".hidden_file"), null, null));

    assertTrue(customValidator.shouldIgnore(Path.of(".hidden_file")));
  }

  @Test
  @DisplayName("Should ignore when given additional configured extension")
  void shouldIgnoreWhenGivenAdditionalConfiguredExtension() {
    var customValidator =
        new IgnoredFileValidator(
            new LibraryScanProperties(null, List.of("srt"), null));

    assertTrue(customValidator.shouldIgnore(Path.of("movie.srt")));
  }

  @Test
  @DisplayName("Should ignore when given additional configured prefix")
  void shouldIgnoreWhenGivenAdditionalConfiguredPrefix() {
    var customValidator =
        new IgnoredFileValidator(
            new LibraryScanProperties(null, null, List.of("~")));

    assertTrue(customValidator.shouldIgnore(Path.of("~tempfile")));
  }
}
