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
      new IgnoredFileValidator(new LibraryScanProperties(null, null, null, null));

  @Test
  @DisplayName("Should ignore when given .DS_Store file")
  void shouldIgnoreWhenGivenDsStoreFile() {
    assertTrue(validator.shouldIgnore(Path.of(".DS_Store")));
  }

  @Test
  @DisplayName("Should ignore when given Thumbs.db file")
  void shouldIgnoreWhenGivenThumbsDbFile() {
    assertTrue(validator.shouldIgnore(Path.of("Thumbs.db")));
  }

  @Test
  @DisplayName("Should ignore when given desktop.ini file")
  void shouldIgnoreWhenGivenDesktopIniFile() {
    assertTrue(validator.shouldIgnore(Path.of("desktop.ini")));
  }

  @Test
  @DisplayName("Should ignore when given macOS resource fork file")
  void shouldIgnoreWhenGivenMacResourceForkFile() {
    assertTrue(validator.shouldIgnore(Path.of("._movie.mkv")));
  }

  @Test
  @DisplayName("Should ignore when given .nfo metadata file")
  void shouldIgnoreWhenGivenNfoExtension() {
    assertTrue(validator.shouldIgnore(Path.of("movie.nfo")));
  }

  @Test
  @DisplayName("Should ignore when given .jpg image file")
  void shouldIgnoreWhenGivenImageExtension() {
    assertTrue(validator.shouldIgnore(Path.of("poster.jpg")));
  }

  @Test
  @DisplayName("Should ignore when given .tmp temporary file")
  void shouldIgnoreWhenGivenTmpExtension() {
    assertTrue(validator.shouldIgnore(Path.of("download.tmp")));
  }

  @Test
  @DisplayName("Should ignore when given .part partial download file")
  void shouldIgnoreWhenGivenPartExtension() {
    assertTrue(validator.shouldIgnore(Path.of("movie.mkv.part")));
  }

  @Test
  @DisplayName("Should not ignore when given .mkv video file")
  void shouldNotIgnoreWhenGivenMkvFile() {
    assertFalse(validator.shouldIgnore(Path.of("About Time (2013).mkv")));
  }

  @Test
  @DisplayName("Should not ignore when given .mp4 video file")
  void shouldNotIgnoreWhenGivenMp4File() {
    assertFalse(validator.shouldIgnore(Path.of("movie.mp4")));
  }

  @Test
  @DisplayName("Should ignore when given uppercase extension")
  void shouldIgnoreWhenGivenUppercaseExtension() {
    assertTrue(validator.shouldIgnore(Path.of("poster.JPG")));
  }

  @Test
  @DisplayName("Should ignore when given uppercase filename")
  void shouldIgnoreWhenGivenUppercaseFilename() {
    assertTrue(validator.shouldIgnore(Path.of("THUMBS.DB")));
  }

  @Test
  @DisplayName("Should not ignore when given file with no extension")
  void shouldNotIgnoreWhenGivenFileWithNoExtension() {
    assertFalse(validator.shouldIgnore(Path.of("README")));
  }

  @Test
  @DisplayName("Should ignore when given additional configured filename")
  void shouldIgnoreWhenGivenAdditionalConfiguredFilename() {
    var customValidator =
        new IgnoredFileValidator(
            new LibraryScanProperties(List.of(".hidden_file"), null, null, null));

    assertTrue(customValidator.shouldIgnore(Path.of(".hidden_file")));
  }

  @Test
  @DisplayName("Should ignore when given additional configured extension")
  void shouldIgnoreWhenGivenAdditionalConfiguredExtension() {
    var customValidator =
        new IgnoredFileValidator(new LibraryScanProperties(null, List.of("srt"), null, null));

    assertTrue(customValidator.shouldIgnore(Path.of("movie.srt")));
  }

  @Test
  @DisplayName("Should ignore when given additional configured prefix")
  void shouldIgnoreWhenGivenAdditionalConfiguredPrefix() {
    var customValidator =
        new IgnoredFileValidator(new LibraryScanProperties(null, null, List.of("~"), null));

    assertTrue(customValidator.shouldIgnore(Path.of("~tempfile")));
  }
}
