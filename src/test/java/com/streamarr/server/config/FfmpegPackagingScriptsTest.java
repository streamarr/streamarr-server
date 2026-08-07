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

  private static final Path BUILDPACK = Path.of("buildpacks/ffmpeg/bin/build").toAbsolutePath();
  private static final Path IMAGE_VERIFIER =
      Path.of(".github/actions/pack-build/verify-ffmpeg-image.sh").toAbsolutePath();

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should contribute an FFmpeg launch dependency with an SBOM when buildpack runs")
  void shouldContributeFfmpegLaunchDependencyWithAnSbomWhenBuildpackRuns() throws Exception {
    var buildpack = buildpack();
    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(buildpack.layer().resolve("bin/ffmpeg")).exists().isExecutable();
    assertThat(Files.readString(buildpack.layers().resolve("ffmpeg.toml")))
        .contains("launch = true", "cache = true");

    var sbom =
        new tools.jackson.databind.ObjectMapper()
            .readTree(Files.readString(buildpack.layers().resolve("ffmpeg.sbom.cdx.json")));
    assertThat(sbom.path("components").get(0).path("name").asString()).isEqualTo("FFmpeg");
    assertThat(sbom.path("components").get(0).path("version").asString())
        .isEqualTo("n8.1.2-34-g9b6c8969e0");
  }

  @Test
  @DisplayName("Should expose FFmpeg through the launch layer bin directory when buildpack runs")
  void shouldExposeFfmpegThroughTheLaunchLayerBinDirectoryWhenBuildpackRuns() throws Exception {
    var buildpack = buildpack();
    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(buildpack.layer().resolve("bin/ffmpeg")).exists().isExecutable();
    assertThat(Files.readString(buildpack.layers().resolve("ffmpeg.toml")))
        .contains("launch = true");
    assertThat(buildpack.layer().resolve("env.launch")).doesNotExist();
  }

  @Test
  @DisplayName("Should remove obsolete PATH metadata when reusing cached FFmpeg layer")
  void shouldRemoveObsoletePathMetadataWhenReusingCachedFfmpegLayer() throws Exception {
    var buildpack = buildpack();
    assertThat(buildpack.execute().exitCode()).isZero();
    var obsoleteLaunchEnvironment = Files.createDirectory(buildpack.layer().resolve("env.launch"));
    Files.writeString(obsoleteLaunchEnvironment.resolve("PATH.prepend"), "obsolete");

    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(obsoleteLaunchEnvironment).doesNotExist();
  }

  private BuildpackFixture buildpack() throws IOException {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var layers = Files.createDirectory(temporaryDirectory.resolve("layers"));
    var layer = layers.resolve("ffmpeg");
    writeCommand(
        commands,
        "curl",
        """
        while (( $# > 0 )); do
          if [[ "$1" == "--output" ]]; then
            archive="$2"
            shift 2
            continue
          fi
          shift
        done
        : > "${archive}"
        """);
    writeCommand(commands, "sha256sum", "cat >/dev/null");
    writeCommand(
        commands,
        "tar",
        """
        mkdir -p "${FAKE_FFMPEG_LAYER}/bin"
        cat >"${FAKE_FFMPEG_LAYER}/bin/ffmpeg" <<'SCRIPT'
        #!/bin/bash
        if [[ "$*" == *"-version"* ]]; then
          printf '%s\n' \
            'ffmpeg version n8.1.2-34-g9b6c8969e0-20260731' \
            'configuration: --enable-gpl --disable-libfdk-aac'
        fi
        if [[ "$*" == *"muxer=hls"* ]]; then
          printf '%s\n' '-hls_segment_options'
        fi
        SCRIPT
        cp "${FAKE_FFMPEG_LAYER}/bin/ffmpeg" "${FAKE_FFMPEG_LAYER}/bin/ffprobe"
        chmod +x "${FAKE_FFMPEG_LAYER}/bin/ffmpeg" "${FAKE_FFMPEG_LAYER}/bin/ffprobe"
        printf '%s\n' 'GNU General Public License' >"${FAKE_FFMPEG_LAYER}/LICENSE.txt"
        """);

    return new BuildpackFixture(
        layers,
        layer,
        command(BUILDPACK)
            .prependPath(commands)
            .environment("CNB_LAYERS_DIR", layers.toString())
            .environment("CNB_TARGET_ARCH", "amd64")
            .environment("FAKE_FFMPEG_LAYER", layer.toString()));
  }

  @Test
  @DisplayName("Should reject unsupported architecture when FFmpeg buildpack runs")
  void shouldRejectUnsupportedArchitectureWhenFfmpegBuildpackRuns() throws Exception {
    var result =
        command(BUILDPACK)
            .environment(
                "CNB_LAYERS_DIR", temporaryDirectory.resolve("layers").toAbsolutePath().toString())
            .environment("CNB_TARGET_ARCH", "riscv64")
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Unsupported FFmpeg target architecture: riscv64");
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
        if [[ "$1 $2" == "image inspect" ]]; then
          case "$*" in
            *org.opencontainers.image.version*) printf '%s\n' 'test' ;;
            *org.opencontainers.image.source*) printf '%s\n' 'https://github.com/streamarr/streamarr-server' ;;
            *org.opencontainers.image.revision*) printf '%s\n' 'abc123' ;;
          esac
          exit
        fi
        if [[ " $* " != *" --interactive "* ]]; then
          exit
        fi
        PATH="${FAKE_FFMPEG_ROOT}/bin:${PATH}" exec /bin/bash -euo pipefail -s
        """);
    writeCommand(
        runtime,
        "ffmpeg",
        """
        printf '%s\\n' \\
          'ffmpeg version n8.1.2-34-g9b6c8969e0-20260731' \\
          'configuration: --enable-gpl --disable-libfdk-aac --enable-nonfree'
        """);
    writeCommand(runtime, "ffprobe", ":");

    var result =
        command(IMAGE_VERIFIER)
            .argument("streamarr/streamarr-server:test")
            .argument("test")
            .argument("https://github.com/streamarr/streamarr-server")
            .argument("abc123")
            .prependPath(commands)
            .environment("FAKE_FFMPEG_ROOT", runtime.getParent().toString())
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg must not include nonfree components");
  }

  @Test
  @DisplayName("Should reject mismatched OCI identity when verifying packaged image")
  void shouldRejectMismatchedOciIdentityWhenVerifyingPackagedImage() throws Exception {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    writeCommand(
        commands,
        "docker",
        """
        if [[ "$1 $2" == "image inspect" ]]; then
          case "$*" in
            *org.opencontainers.image.version*) printf '%s\n' '0.0.1-SNAPSHOT' ;;
            *org.opencontainers.image.source*) printf '%s\n' 'https://github.com/streamarr/streamarr-server' ;;
            *org.opencontainers.image.revision*) printf '%s\n' 'abc123' ;;
          esac
        fi
        """);

    var result =
        command(IMAGE_VERIFIER)
            .argument("streamarr/streamarr-server:test")
            .argument("1.2.3")
            .argument("https://github.com/streamarr/streamarr-server")
            .argument("abc123")
            .prependPath(commands)
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output())
        .contains("Expected org.opencontainers.image.version=1.2.3 but found 0.0.1-SNAPSHOT");
  }

  @Test
  @DisplayName("Should pull published image before verification when image is not local")
  void shouldPullPublishedImageBeforeVerificationWhenImageIsNotLocal() throws Exception {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var pullMarker = temporaryDirectory.resolve("pulled");
    writeCommand(
        commands,
        "docker",
        """
        if [[ "$1 $2" == "image inspect" && "$*" != *"--format"* ]]; then
          [[ -f "${PULL_MARKER}" ]]
          exit
        fi
        if [[ "$1" == "pull" ]]; then
          : >"${PULL_MARKER}"
          exit
        fi
        if [[ "$1 $2" == "image inspect" ]]; then
          case "$*" in
            *org.opencontainers.image.version*) printf '%s\n' '1.2.3' ;;
            *org.opencontainers.image.source*) printf '%s\n' 'https://github.com/streamarr/streamarr-server' ;;
            *org.opencontainers.image.revision*) printf '%s\n' 'abc123' ;;
          esac
        fi
        """);

    var result =
        command(IMAGE_VERIFIER)
            .argument("streamarr/streamarr-server:test")
            .argument("1.2.3")
            .argument("https://github.com/streamarr/streamarr-server")
            .argument("abc123")
            .prependPath(commands)
            .environment("PULL_MARKER", pullMarker.toString())
            .execute();

    assertThat(result.exitCode()).isZero();
    assertThat(pullMarker).exists();
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

  private record BuildpackFixture(Path layers, Path layer, CommandFixture command) {

    private ExecutionResult execute() throws IOException, InterruptedException {
      return command.execute();
    }
  }

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
