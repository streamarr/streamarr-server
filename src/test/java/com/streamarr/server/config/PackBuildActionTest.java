package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Pack Build Action Tests")
class PackBuildActionTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("Should expose a verified local image when the native checks pass")
  void shouldExposeVerifiedLocalImageWhenNativeChecksPass() throws Exception {
    var command = build();

    var result = run(command);

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(directory.resolve("registry-image")).doesNotExist();
    assertThat(directory.resolve(".github/actions")).doesNotExist();
    assertThat(Files.readString(directory.resolve("outputs")))
        .isEqualTo("image=index.docker.io/streamarr/streamarr-server:build-10-1-x86_64\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"server", "cedar"})
  @DisplayName("Should leave the registry untouched when a native image check fails")
  void shouldLeaveRegistryUntouchedWhenNativeImageCheckFails(String failedCheck) throws Exception {
    var command = build();
    command.environment().put("FAILED_CHECK", failedCheck);

    var result = run(command);

    assertThat(result.exitCode()).as(result.output()).isEqualTo(23);
    assertThat(directory.resolve("registry-image")).doesNotExist();
    assertThat(directory.resolve("outputs")).doesNotExist();
  }

  private ProcessBuilder build() throws Exception {
    stub(
        "pack",
        """
        for argument in "$@"; do
          if [ "$argument" = --publish ]; then touch registry-image; fi
        done
        touch local-image
        """);
    stub("git", "printf '%040d' 1\n");
    stub("uname", "echo x86_64\n");
    for (var check : List.of("server", "cedar")) {
      stub(
          "workflow-action/verify-" + check + "-image.sh",
          "[ -f local-image ] || exit 24\n[ \"${FAILED_CHECK:-}\" != " + check + " ] || exit 23\n");
    }

    Map<String, Object> action =
        new Yaml().load(Files.readString(Path.of(".github/actions/pack-build/action.yml")));
    var steps = (List<?>) ((Map<?, ?>) action.get("runs")).get("steps");
    var script =
        steps.stream()
            .map(step -> (Map<?, ?>) step)
            .filter(step -> "Build with pack CLI".equals(step.get("name")))
            .findFirst()
            .orElseThrow()
            .get("run")
            .toString();
    var command = new ProcessBuilder("bash", "-euo", "pipefail", "-c", script);
    command.directory(directory.toFile());
    command
        .environment()
        .putAll(
            Map.of(
                "PATH", directory + ":" + System.getenv("PATH"),
                "INPUT_PUBLISH", "true",
                "ACTION_PATH", directory.resolve("workflow-action").toString(),
                "INPUT_IMAGE_VERSION", "1.2.3-SNAPSHOT",
                "GITHUB_RUN_ID", "10",
                "GITHUB_RUN_ATTEMPT", "1",
                "GITHUB_SERVER_URL", "https://github.com",
                "GITHUB_REPOSITORY", "streamarr/streamarr-server",
                "GITHUB_OUTPUT", directory.resolve("outputs").toString()));
    return command;
  }

  private void stub(String name, String content) throws Exception {
    var file = directory.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "#!/bin/sh\nset -eu\n" + content);
    assertThat(file.toFile().setExecutable(true)).isTrue();
  }

  private Result run(ProcessBuilder command) throws Exception {
    var log = directory.resolve("command.log");
    var process = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Pack command completed").isTrue();
    return new Result(process.exitValue(), Files.readString(log));
  }

  private record Result(int exitCode, String output) {}
}
