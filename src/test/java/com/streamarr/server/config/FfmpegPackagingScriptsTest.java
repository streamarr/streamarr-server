package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFmpeg Packaging Script Tests")
class FfmpegPackagingScriptsTest {

  private static final Path INSTALLER =
      Path.of(".github/actions/pack-build/install-ffmpeg.sh").toAbsolutePath();
  private static final Path IMAGE_VERIFIER =
      Path.of(".github/actions/pack-build/verify-ffmpeg-image.sh").toAbsolutePath();

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should reject unsupported architecture when installing FFmpeg")
  void shouldRejectUnsupportedArchitectureWhenInstallingFfmpeg() throws Exception {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    writeCommand(commands, "uname", "printf '%s\\n' riscv64");

    var result =
        command(INSTALLER)
            .argument(temporaryDirectory.resolve("ffmpeg").toString())
            .prependPath(commands)
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Unsupported FFmpeg target architecture: riscv64");
  }

  @Test
  @DisplayName("Should reject existing destination when installing FFmpeg")
  void shouldRejectExistingDestinationWhenInstallingFfmpeg() throws Exception {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    writeCommand(commands, "uname", "printf '%s\\n' x86_64");
    var destination = Files.createDirectory(temporaryDirectory.resolve("ffmpeg"));

    var result =
        command(INSTALLER).argument(destination.toString()).prependPath(commands).execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg destination already exists: " + destination);
  }

  @Test
  @DisplayName("Should reject nonfree runtime when verifying packaged image")
  void shouldRejectNonfreeRuntimeWhenVerifyingPackagedImage() throws Exception {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var runtime = Files.createDirectories(temporaryDirectory.resolve("runtime/bin"));
    writeCommand(
        commands,
        "docker",
        """
        script="${!#}"
        script="${script//\\/workspace\\/.ffmpeg/${FAKE_FFMPEG_ROOT}}"
        exec /bin/bash -euo pipefail -c "${script}"
        """);
    writeCommand(
        runtime,
        "ffmpeg",
        """
        printf '%s\\n' \\
          'ffmpeg version n8.1.2-34-g9b6c8969e0-20260731' \\
          'configuration: --enable-gpl --disable-libfdk-aac --enable-nonfree'
        """);

    var result =
        command(IMAGE_VERIFIER)
            .argument("streamarr/streamarr-server:test")
            .prependPath(commands)
            .environment("FAKE_FFMPEG_ROOT", runtime.getParent().toString())
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg must not include nonfree components");
  }

  private CommandFixture command(Path script) {
    return new CommandFixture(script);
  }

  private static void writeCommand(Path directory, String name, String body) throws IOException {
    var command = directory.resolve(name);
    Files.writeString(command, "#!/bin/bash\nset -euo pipefail\n" + body);
    assertThat(command.toFile().setExecutable(true)).isTrue();
  }

  private record ExecutionResult(int exitCode, String output) {}

  private static final class CommandFixture {

    private final List<String> command = new ArrayList<>();
    private final Map<String, String> environment = new HashMap<>();
    private Path prependedPath;

    private CommandFixture(Path script) {
      command.add(script.toString());
    }

    private CommandFixture argument(String argument) {
      command.add(argument);
      return this;
    }

    private CommandFixture environment(String name, String value) {
      environment.put(name, value);
      return this;
    }

    private CommandFixture prependPath(Path path) {
      prependedPath = path;
      return this;
    }

    private ExecutionResult execute() throws IOException, InterruptedException {
      var processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
      processBuilder.environment().putAll(environment);
      if (prependedPath != null) {
        var systemPath = processBuilder.environment().get("PATH");
        processBuilder.environment().put("PATH", prependedPath + ":" + systemPath);
      }

      var process = processBuilder.start();
      var output = new String(process.getInputStream().readAllBytes());
      return new ExecutionResult(process.waitFor(), output);
    }
  }
}
