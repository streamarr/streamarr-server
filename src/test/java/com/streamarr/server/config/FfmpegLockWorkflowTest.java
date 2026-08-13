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
    assertThat(map(filter.get("with")).get("filters").toString())
        .contains(
            "- 'buildpacks/**'",
            "- '.github/actions/pack-build/**'",
            "- '.github/workflows/ci.yml'");
    assertThat(offline).doesNotContainKeys("if", "env");
    assertThat((String) offline.get("run")).contains("--check").doesNotContain("--verify-upstream");
    assertThat(upstream).containsEntry("if", "needs.changes.outputs.packaging == 'true'");
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
  @DisplayName("Should verify canonical FFmpeg metadata before building release images")
  void shouldVerifyCanonicalFfmpegMetadataBeforeBuildingReleaseImages() throws IOException {
    var workflow = yaml(".github/workflows/publish-release.yml");
    var release = map(map(workflow.get("jobs")).get("build_release_images"));
    var steps = listOfMaps(release.get("steps"));
    var verify = stepNamed(steps, "Verify FFmpeg lock");

    assertThat(steps.stream().map(step -> step.get("name")))
        .containsSubsequence("Verify FFmpeg lock", "Docker Metadata");
    assertThat((String) verify.get("run")).contains("--verify-upstream");
    assertThat(map(verify.get("env"))).containsEntry("GITHUB_TOKEN", "${{ github.token }}");
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
