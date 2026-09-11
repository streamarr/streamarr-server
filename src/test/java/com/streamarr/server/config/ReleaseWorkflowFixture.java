package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.Yaml;

@RequiredArgsConstructor
class ReleaseWorkflowFixture {

  private final Path directory;

  ProcessBuilder step(String workflow, String name) throws Exception {
    Map<String, Object> source =
        new Yaml().load(Files.readString(Path.of(".github/workflows/" + workflow + ".yml")));
    var step =
        ((Map<?, ?>) source.get("jobs"))
            .values().stream()
                .flatMap(job -> ((List<?>) ((Map<?, ?>) job).get("steps")).stream())
                .map(item -> (Map<?, ?>) item)
                .filter(item -> name.equals(item.get("name")))
                .findFirst()
                .orElseThrow();
    var flags = new ArrayList<>(List.of("bash", "-e"));
    if ("bash".equals(step.get("shell"))) {
      flags.addAll(List.of("-o", "pipefail"));
    }

    flags.addAll(List.of("-c", (String) step.get("run")));
    var command = new ProcessBuilder(flags).directory(directory.toFile());
    command
        .environment()
        .putAll(
            Map.of(
                "PATH",
                directory + ":" + System.getenv("PATH"),
                "GITHUB_REPOSITORY",
                "streamarr/streamarr-server",
                "GITHUB_OUTPUT",
                directory.resolve("outputs").toString()));
    return command;
  }

  void stub(String name, String script) throws Exception {
    var file = directory.resolve(name);
    Files.writeString(file, "#!/bin/sh\n" + script);
    assertThat(file.toFile().setExecutable(true)).isTrue();
  }

  String git(String... arguments) throws Exception {
    var args = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
    args.addAll(List.of(arguments));
    var command = new ProcessBuilder(args).directory(directory.toFile());
    command
        .environment()
        .putAll(
            Map.of(
                "GIT_CONFIG_NOSYSTEM",
                "1",
                "GIT_CONFIG_GLOBAL",
                "/dev/null",
                "GIT_AUTHOR_NAME",
                "Release fixture",
                "GIT_AUTHOR_EMAIL",
                "fixture@example.test",
                "GIT_COMMITTER_NAME",
                "Release fixture",
                "GIT_COMMITTER_EMAIL",
                "fixture@example.test"));
    var result = run(command);
    assertThat(result.exitCode()).as(result.output()).isZero();
    return result.output().trim();
  }

  static Result run(ProcessBuilder command) throws Exception {
    var process = command.redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes());
    return new Result(process.waitFor(), output);
  }

  record Result(int exitCode, String output) {}
}
