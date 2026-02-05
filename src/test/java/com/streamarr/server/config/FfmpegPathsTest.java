package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
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
  @DisplayName("Should resolve from search paths when no override provided")
  void shouldResolveFromSearchPathsWhenNoOverrideProvided(@TempDir Path tempDir)
      throws IOException {
    var paths = FfmpegPaths.resolve(null, null);

    assertThat(paths.ffmpeg()).isNotBlank();
    assertThat(paths.ffprobe()).isNotBlank();
  }

  @Test
  @DisplayName("Should ignore blank override and resolve from search paths")
  void shouldIgnoreBlankOverrideAndResolveFromSearchPaths() {
    var paths = FfmpegPaths.resolve("  ", "  ");

    assertThat(paths.ffmpeg()).isNotBlank();
    assertThat(paths.ffprobe()).isNotBlank();
    assertThat(paths.ffmpeg().isBlank()).isFalse();
  }

  @Test
  @DisplayName("Should fall back to first candidate when no executable found")
  void shouldFallBackToFirstCandidateWhenNoExecutableFound() {
    var paths = FfmpegPaths.resolve(null, null);

    assertThat(paths.ffmpeg()).isNotNull();
    assertThat(paths.ffprobe()).isNotNull();
  }
}
