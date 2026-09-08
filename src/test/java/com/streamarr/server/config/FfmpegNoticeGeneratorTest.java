package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFmpeg Notice Generator Tests")
class FfmpegNoticeGeneratorTest {

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should pass the offline generator contract tests")
  void shouldPassOfflineGeneratorContractTests() throws Exception {
    assertSuccessful(List.of("node", "--test", "buildpacks/ffmpeg/test/generate-notices.test.mjs"));
  }

  @Test
  @DisplayName("Should reproduce the checked-in redistribution materials exactly")
  void shouldReproduceCheckedInRedistributionMaterialsExactly() throws Exception {
    assertSuccessful(List.of("node", "buildpacks/ffmpeg/bin/generate-notices.mjs", "--check"));
  }

  private void assertSuccessful(List<String> command) throws Exception {
    var output = temporaryDirectory.resolve("output.txt");
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(60, TimeUnit.SECONDS))
          .as("Generator command completed: %s", command)
          .isTrue();
      assertThat(process.exitValue()).as(Files.readString(output)).isZero();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
