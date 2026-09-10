package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Release Workflow Tests")
class ReleaseWorkflowTest {

  @Test
  @DisplayName("Should use dedicated release credentials when minting the App token")
  void shouldUseDedicatedReleaseCredentialsWhenMintingAppToken() throws Exception {
    Map<String, Object> workflow =
        new Yaml().load(Files.readString(Path.of(".github/workflows/release-please.yml")));
    var release = map(map(workflow.get("jobs")).get("release"));
    var token =
        steps(release).stream()
            .filter(step -> "Mint release bot token".equals(step.get("name")))
            .findFirst()
            .orElseThrow();

    assertThat(map(token.get("with")))
        .containsEntry("client-id", "${{ secrets.RELEASE_APP_CLIENT_ID }}")
        .containsEntry("private-key", "${{ secrets.RELEASE_APP_PRIVATE_KEY }}")
        .containsEntry("permission-contents", "write")
        .containsEntry("permission-pull-requests", "write")
        .containsEntry("permission-issues", "write");
  }

  @ParameterizedTest
  @CsvSource({"v1.2.3, true", "v1.2.4, false"})
  @DisplayName("Should promote latest only when publishing the current GitHub release")
  void shouldPromoteLatestOnlyWhenPublishingCurrentGitHubRelease(
      String latestTag, boolean promoted, @TempDir Path directory) throws Exception {
    Map<String, Object> workflow =
        new Yaml().load(Files.readString(Path.of(".github/workflows/publish-release.yml")));
    var publish = map(map(workflow.get("jobs")).get("publish_release"));
    var command =
        steps(publish).stream()
            .filter(step -> "Publish latest multi-architecture image".equals(step.get("name")))
            .map(step -> (String) step.get("run"))
            .findFirst()
            .orElseThrow();
    Files.writeString(directory.resolve("gh"), "#!/bin/sh\necho \"$LATEST_TAG\"\n");
    Files.writeString(directory.resolve("docker"), "#!/bin/sh\ntouch \"$PROMOTED_FILE\"\n");
    assertThat(directory.resolve("gh").toFile().setExecutable(true)).isTrue();
    assertThat(directory.resolve("docker").toFile().setExecutable(true)).isTrue();
    var marker = directory.resolve("promoted");
    var builder = new ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", command);
    builder.environment().put("PATH", directory + ":" + System.getenv("PATH"));
    builder.environment().put("LATEST_TAG", latestTag);
    builder.environment().put("IMAGE_VERSION", "1.2.3");
    builder.environment().put("GITHUB_REPOSITORY", "streamarr/streamarr-server");
    builder.environment().put("PROMOTED_FILE", marker.toString());
    var process = builder.redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes());

    assertThat(process.waitFor()).as(output).isZero();
    assertThat(Files.exists(marker)).isEqualTo(promoted);
  }

  @Test
  @DisplayName("Should maintain release PRs on main with serialized trusted automation")
  void shouldMaintainReleasePrsOnMainWithSerializedTrustedAutomation() throws Exception {
    var source = Files.readString(Path.of(".github/workflows/release-please.yml"));
    Map<String, Object> workflow = new Yaml().load(source);

    assertThat(source)
        .contains("push:", "- main", "workflow_dispatch:")
        .doesNotContain("pull_request_target:", "pull_request:");
    assertThat(map(workflow.get("permissions"))).containsOnly(Map.entry("contents", "read"));
    assertThat(map(workflow.get("concurrency"))).containsEntry("cancel-in-progress", false);
    assertThat(Path.of(".github/workflows/release-draft.yml")).doesNotExist();
    assertThat(Path.of(".github/release-drafter.yml")).doesNotExist();
    var run = Files.readString(Path.of(".github/release/run.cjs"));
    assertThat(run).contains("--auto", "--match-head-commit", "--body', ''");
  }

  @Test
  @DisplayName("Should reject version drift when validating a release")
  void shouldRejectVersionDriftWhenValidatingRelease() throws Exception {
    var process =
        new ProcessBuilder("node", "--test", ".github/release/verify-version.test.cjs")
            .redirectErrorStream(true)
            .start();
    var output = new String(process.getInputStream().readAllBytes());

    assertThat(process.waitFor()).as(output).isZero();
  }

  @Test
  @DisplayName("Should pin both image architectures to the validated release revision")
  void shouldPinBothImageArchitecturesToValidatedReleaseRevision() throws Exception {
    Map<String, Object> workflow =
        new Yaml().load(Files.readString(Path.of(".github/workflows/publish-release.yml")));
    var jobs = map(workflow.get("jobs"));

    assertThat(jobs).containsKey("validate_release");
    var build = map(jobs.get("build_release_images"));
    assertThat(build).containsEntry("needs", "validate_release");
    var steps = steps(build);
    var checkout =
        steps.stream()
            .filter(step -> String.valueOf(step.get("uses")).startsWith("actions/checkout@"))
            .findFirst()
            .orElseThrow();
    assertThat(map(checkout.get("with")))
        .containsEntry("ref", "${{ needs.validate_release.outputs.revision }}");
    var pack =
        steps.stream()
            .filter(step -> "./.github/actions/pack-build".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();
    assertThat(map(pack.get("with")))
        .containsEntry("image-version", "${{ needs.validate_release.outputs.version }}");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> steps(Map<String, Object> job) {
    return (List<Map<String, Object>>) job.get("steps");
  }
}
