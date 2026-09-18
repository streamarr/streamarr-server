package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Server Image Verification Tests")
class ServerImageVerificationTest {

  @TempDir private Path temporaryDirectory;

  @Test
  @DisplayName("Should accept an image when metadata matches and media executables are absent")
  void shouldAcceptImageWhenMetadataMatchesAndMediaExecutablesAreAbsent() throws Exception {
    var result = verifyImage(Map.of());

    assertThat(result.exitCode()).as(result.output()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"VERSION, version", "SOURCE, source", "REVISION, revision"})
  @DisplayName("Should reject an image when an OCI identity label differs")
  void shouldRejectImageWhenOciIdentityLabelDiffers(String variable, String label)
      throws Exception {
    var result = verifyImage(Map.of(variable, "incorrect"));

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("Expected org.opencontainers.image." + label);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ffmpeg", "ffprobe"})
  @DisplayName("Should reject an image when media executables are bundled")
  void shouldRejectImageWhenMediaExecutablesAreBundled(String executable) throws Exception {
    var result = verifyImage(Map.of("MEDIA_EXECUTABLE", executable));

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("Server image must not contain FFmpeg or ffprobe");
  }

  @Test
  @DisplayName("Should pull an image when verification starts without a local copy")
  void shouldPullImageWhenVerificationStartsWithoutLocalCopy() throws Exception {
    var result = verifyImage(Map.of("IMAGE_PRESENT", "false"));

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(temporaryDirectory.resolve("available-image")).exists();
  }

  private CommandResult verifyImage(Map<String, String> overrides) throws Exception {
    var docker = temporaryDirectory.resolve("docker");
    Files.writeString(
        docker,
        """
        #!/bin/bash
        set -euo pipefail
        if [[ "$1 $2" == 'image inspect' ]]; then
          if [[ "$*" != *'--format'* ]]; then
            [[ -f "$AVAILABLE_IMAGE" ]]
            exit
          fi
          case "$*" in
            *org.opencontainers.image.version*) echo "$VERSION" ;;
            *org.opencontainers.image.source*) echo "$SOURCE" ;;
            *org.opencontainers.image.revision*) echo "$REVISION" ;;
            *) exit 2 ;;
          esac
          exit
        fi
        if [[ "$1" == pull ]]; then
          touch "$AVAILABLE_IMAGE"
          exit
        fi
        if [[ "$1" == run ]]; then
          while [[ "$1" != /bin/bash ]]; do
            shift
          done
          PATH="$IMAGE_BIN" exec "$@"
        fi
        exit 2
        """);
    assertThat(docker.toFile().setExecutable(true)).isTrue();
    var settings =
        new HashMap<>(
            Map.of(
                "VERSION", "1.2.3",
                "SOURCE", "https://github.com/streamarr/streamarr-server",
                "REVISION", "abc123",
                "MEDIA_EXECUTABLE", "",
                "IMAGE_PRESENT", "true"));
    settings.putAll(overrides);
    var imageBin = Files.createDirectory(temporaryDirectory.resolve("image-bin"));
    if (!settings.get("MEDIA_EXECUTABLE").isEmpty()) {
      var executable = imageBin.resolve(settings.get("MEDIA_EXECUTABLE"));
      Files.writeString(executable, "#!/bin/sh\nexit 0\n");
      assertThat(executable.toFile().setExecutable(true)).isTrue();
    }

    var availableImage = temporaryDirectory.resolve("available-image");
    if (settings.get("IMAGE_PRESENT").equals("true")) {
      Files.createFile(availableImage);
    }

    var output = temporaryDirectory.resolve("command.log");
    var builder =
        new ProcessBuilder(
            "bash", ".github/actions/pack-build/verify-server-image.sh",
            "streamarr/streamarr-server:test", "1.2.3",
            "https://github.com/streamarr/streamarr-server", "abc123");
    builder.environment().putAll(settings);
    builder.environment().put("PATH", temporaryDirectory + ":" + System.getenv("PATH"));
    builder.environment().put("AVAILABLE_IMAGE", availableImage.toString());
    builder.environment().put("IMAGE_BIN", imageBin.toString());
    var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("image verification completed").isTrue();
      return new CommandResult(process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  private record CommandResult(int exitCode, String output) {}
}
