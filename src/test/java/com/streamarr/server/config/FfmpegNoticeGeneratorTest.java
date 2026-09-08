package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFmpeg Notice Generator Tests")
class FfmpegNoticeGeneratorTest {

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void requireToolchain() throws Exception {
    FfmpegTestToolchain.requireNode();
  }

  @Test
  @DisplayName("Should pass the offline generator contract tests")
  void shouldPassOfflineGeneratorContractTests() throws Exception {
    assertSuccessful(
        List.of(
            "node",
            "--experimental-test-coverage",
            "--test-coverage-include=**/bin/generate-notices.mjs",
            "--test-coverage-lines=90",
            "--test-coverage-branches=85",
            "--test-coverage-functions=90",
            "--test",
            "buildpacks/ffmpeg/test/generate-notices.test.mjs"));
  }

  @Test
  @DisplayName("Should reproduce redistribution materials from clean inputs")
  void shouldReproduceRedistributionMaterialsFromCleanInputs() throws Exception {
    var original = Path.of("buildpacks/ffmpeg");
    var copy = temporaryDirectory.resolve("ffmpeg");
    try (var paths = Files.walk(original)) {
      for (var path :
          paths
              .filter(Files::isRegularFile)
              .filter(path -> !path.startsWith(original.resolve("generated")))
              .toList()) {
        var destination = copy.resolve(original.relativize(path));
        Files.createDirectories(destination.getParent());
        Files.copy(path, destination);
      }
    }

    assertSuccessful(
        List.of(
            "node",
            "buildpacks/ffmpeg/bin/generate-notices.mjs",
            "--root",
            copy.toString(),
            "--validate"));
    assertThat(copy.resolve("generated")).doesNotExist();
    assertSuccessful(
        List.of("node", "buildpacks/ffmpeg/bin/generate-notices.mjs", "--root", copy.toString()));
    assertSuccessful(
        List.of(
            "node",
            "buildpacks/ffmpeg/bin/generate-notices.mjs",
            "--root",
            copy.toString(),
            "--check"));
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
