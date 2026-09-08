package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("FFmpeg Lock Workflow Tests")
class FfmpegLockWorkflowTest {

  @Test
  @DisplayName("Should keep offline lock validation independent from upstream availability")
  void shouldKeepOfflineLockValidationIndependentFromUpstreamAvailability() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var jobs = map(workflow.get("jobs"));

    assertThat(jobs).containsKey("ffmpeg_lock");

    var lock = map(jobs.get("ffmpeg_lock"));
    var changes = map(jobs.get("changes"));
    var steps = listOfMaps(lock.get("steps"));
    var filter = stepNamed(listOfMaps(changes.get("steps")), "Filter changed paths");
    var offline = stepNamed(steps, "Validate FFmpeg lock offline");
    var upstream = stepNamed(steps, "Verify FFmpeg lock against upstream");

    assertThat(lock)
        .containsEntry("needs", "changes")
        .containsEntry("permissions", Map.of("contents", "read"));
    assertThat(lock.toString()).doesNotContain("secrets.");
    assertThat(map(changes.get("outputs")))
        .containsEntry("ffmpeg", "${{ steps.filter.outputs.ffmpeg }}");
    assertThat(map(filter.get("with")).get("filters").toString())
        .contains(
            "- 'buildpacks/**'",
            "- '.github/actions/pack-build/**'",
            "- '.github/workflows/ci.yml'");
    assertThat(offline).doesNotContainKeys("if", "env");
    assertThat(offline).containsEntry("uses", "./.github/actions/prepare-ffmpeg");
    var filters = yamlFilters(filter);
    assertThat(filters)
        .containsEntry(
            "ffmpeg",
            List.of(
                "buildpacks/ffmpeg/release",
                "buildpacks/ffmpeg/ffmpeg.lock",
                "buildpacks/ffmpeg/bin/update-lock",
                "buildpacks/ffmpeg/lib/**"));
    assertThat(upstream).containsEntry("if", "needs.changes.outputs.ffmpeg == 'true'");
    assertThat((String) upstream.get("run")).contains("--verify-upstream");
    assertThat(map(upstream.get("env"))).containsEntry("GITHUB_TOKEN", "${{ github.token }}");
  }

  @Test
  @DisplayName("Should aggregate every applicable CI result behind the required build status")
  void shouldAggregateEveryApplicableCiResultBehindRequiredBuildStatus() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var jobs = map(workflow.get("jobs"));

    assertThat(jobs).containsKeys("application", "build", "package_image");

    var packageImage = map(jobs.get("package_image"));
    var aggregate = map(jobs.get("build"));
    var verify = stepNamed(listOfMaps(aggregate.get("steps")), "Verify required checks");

    assertThat(packageImage)
        .containsEntry("needs", List.of("changes", "ffmpeg_lock"))
        .containsEntry("if", "needs.changes.outputs.packaging == 'true'");
    assertThat(aggregate)
        .containsEntry("needs", List.of("changes", "ffmpeg_lock", "application", "package_image"))
        .containsEntry("if", "${{ always() }}");
    assertThat((String) verify.get("run"))
        .contains(
            "needs.changes.result",
            "needs.changes.outputs.packaging",
            "needs.ffmpeg_lock.result",
            "needs.application.result",
            "needs.package_image.result");
  }

  @Test
  @DisplayName("Should validate FFmpeg lock offline before building release images")
  void shouldValidateFfmpegLockOfflineBeforeBuildingReleaseImages() throws IOException {
    var workflow = yaml(".github/workflows/publish-release.yml");
    var release = map(map(workflow.get("jobs")).get("build_release_images"));
    var steps = listOfMaps(release.get("steps"));
    var verify = stepNamed(steps, "Verify FFmpeg lock");

    assertThat(steps.stream().map(step -> step.get("name")))
        .containsSubsequence("Verify FFmpeg lock", "Docker Metadata");
    assertThat(verify).containsEntry("uses", "./.github/actions/prepare-ffmpeg");
    assertThat(verify).doesNotContainKeys("env");
  }

  @Test
  @DisplayName("Should keep tooling version markers out of application buildpack detection")
  void shouldKeepToolingVersionMarkersOutOfApplicationBuildpackDetection() {
    assertThat(Path.of(".nvmrc"))
        .as("Paketo Node Engine self-requires Node for a root .nvmrc")
        .doesNotExist();
    assertThat(Path.of(".node-version"))
        .as("Paketo Node Engine also detects a root .node-version")
        .doesNotExist();
    assertThat(Path.of("buildpacks/ffmpeg/.nvmrc")).isRegularFile();
  }

  @Test
  @DisplayName("Should prepare ephemeral redistribution materials with the declared Node toolchain")
  void shouldPrepareEphemeralRedistributionMaterialsWithDeclaredNodeToolchain() throws IOException {
    var prepare =
        listOfMaps(map(yaml(".github/actions/prepare-ffmpeg/action.yml").get("runs")).get("steps"));
    var node = prepare.getFirst();
    assertThat(node.get("uses").toString()).startsWith("actions/setup-node@");
    assertThat(map(node.get("with")))
        .containsEntry("node-version-file", "buildpacks/ffmpeg/.nvmrc");
    var commands =
        stepNamed(prepare, "Prepare reviewed redistribution materials").get("run").toString();
    assertThat(commands)
        .contains("buildpacks/ffmpeg/bin/update-lock --check", "buildpacks/ffmpeg/bin/prepare")
        .doesNotContain("--verify-upstream");
    for (var action : List.of("pack-build", "setup-ffmpeg")) {
      var steps =
          listOfMaps(
              map(yaml(".github/actions/" + action + "/action.yml").get("runs")).get("steps"));
      assertThat(steps.getFirst()).containsEntry("uses", "./.github/actions/prepare-ffmpeg");
    }

    var application = map(map(yaml(".github/workflows/ci.yml").get("jobs")).get("application"));
    var steps = listOfMaps(application.get("steps"));
    assertThat(steps.stream().map(step -> step.get("name")))
        .containsSubsequence("Prepare FFmpeg tooling", "Build and test");
  }

  private static Map<String, Object> yamlFilters(Map<String, Object> step) {
    return new Yaml().load(map(step.get("with")).get("filters").toString());
  }

  private static Map<String, Object> yaml(String file) throws IOException {
    try (var input = Files.newInputStream(Path.of(file))) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listOfMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static Map<String, Object> stepNamed(
      List<Map<String, Object>> steps, String expectedName) {
    return steps.stream()
        .filter(step -> expectedName.equals(step.get("name")))
        .findFirst()
        .orElseThrow();
  }
}
