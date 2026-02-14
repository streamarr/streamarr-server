package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFmpeg Paths Tests")
class FfmpegPathsTest {

  @Test
  @DisplayName("Should use override when ffmpeg override is provided")
  void shouldUseOverrideWhenFfmpegOverrideIsProvided() {
    var paths = FfmpegPaths.resolve("/custom/ffmpeg", null);

    assertThat(paths.ffmpeg()).isEqualTo("/custom/ffmpeg");
  }

  @Test
  @DisplayName("Should use override when ffprobe override is provided")
  void shouldUseOverrideWhenFfprobeOverrideIsProvided() {
    var paths = FfmpegPaths.resolve(null, "/custom/ffprobe");

    assertThat(paths.ffprobe()).isEqualTo("/custom/ffprobe");
  }

  @Test
  @DisplayName("Should resolve absolute paths when no override provided")
  void shouldResolveAbsolutePathsWhenNoOverrideProvided() {
    var paths = FfmpegPaths.resolve(null, null);

    assertThat(Path.of(paths.ffmpeg())).isAbsolute();
    assertThat(Path.of(paths.ffprobe())).isAbsolute();
  }

  @Test
  @DisplayName("Should treat blank override as absent")
  void shouldTreatBlankOverrideAsAbsent() {
    var withNull = FfmpegPaths.resolve(null, null);
    var withBlank = FfmpegPaths.resolve("  ", "  ");

    assertThat(withBlank).isEqualTo(withNull);
  }

  @Test
  @DisplayName("Should resolve ffmpeg from PATH when not at well-known locations")
  void shouldResolveFfmpegFromPathWhenNotAtWellKnownLocations(@TempDir Path tempDir)
      throws IOException {
    var ffmpeg = createExecutable(tempDir, "ffmpeg");
    createExecutable(tempDir, "ffprobe");

    var paths = FfmpegPaths.resolve(null, null, tempDir.toString());

    assertThat(paths.ffmpeg()).isEqualTo(ffmpeg.toString());
  }

  @Test
  @DisplayName("Should fall back to well-known locations when PATH is empty")
  void shouldFallBackToWellKnownLocationsWhenPathIsEmpty() {
    var withNullPath = FfmpegPaths.resolve(null, null, null);
    var withEmptyPath = FfmpegPaths.resolve(null, null, "");
    var withBlankPath = FfmpegPaths.resolve(null, null, "   ");
    var withoutPath = FfmpegPaths.resolve(null, null);

    assertThat(withNullPath).isEqualTo(withoutPath);
    assertThat(withEmptyPath).isEqualTo(withoutPath);
    assertThat(withBlankPath).isEqualTo(withoutPath);
  }

  @Test
  @DisplayName("Should prefer PATH over well-known locations when both are available")
  void shouldPreferPathOverWellKnownLocationsWhenBothAreAvailable(@TempDir Path tempDir)
      throws IOException {
    var ffmpeg = createExecutable(tempDir, "ffmpeg");
    createExecutable(tempDir, "ffprobe");

    var paths = FfmpegPaths.resolve(null, null, tempDir.toString());

    assertThat(paths.ffmpeg()).isEqualTo(ffmpeg.toString());
  }

  private static Path createExecutable(Path dir, String name) throws IOException {
    var file = dir.resolve(name);
    Files.createFile(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file;
  }
}
