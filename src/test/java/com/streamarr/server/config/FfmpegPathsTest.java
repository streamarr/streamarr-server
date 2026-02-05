package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

    assertThat(Path.of(paths.ffmpeg()).isAbsolute()).isTrue();
    assertThat(Path.of(paths.ffprobe()).isAbsolute()).isTrue();
  }

  @Test
  @DisplayName("Should treat blank override as absent")
  void shouldTreatBlankOverrideAsAbsent() {
    var withNull = FfmpegPaths.resolve(null, null);
    var withBlank = FfmpegPaths.resolve("  ", "  ");

    assertThat(withBlank).isEqualTo(withNull);
  }
}
