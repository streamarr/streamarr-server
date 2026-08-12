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
@DisplayName("Preview Workflow Tests")
class PreviewWorkflowTest {

  @Test
  @DisplayName("Should build untrusted preview images without write access or secrets")
  void shouldBuildUntrustedPreviewImagesWithoutWriteAccessOrSecrets() throws IOException {
    var workflow = yaml(".github/workflows/preview.yml");
    var build = map(map(workflow.get("jobs")).get("build_preview"));
    var buildStep =
        listOfMaps(build.get("steps")).stream()
            .filter(step -> "./.github/actions/pack-build".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();

    assertThat(map(workflow.get("permissions"))).isEmpty();
    assertThat(map(build.get("permissions")))
        .containsOnlyKeys("contents")
        .containsEntry("contents", "read");
    assertThat(map(buildStep.get("with")))
        .containsEntry("publish", "false")
        .doesNotContainKeys("dockerhub-username", "dockerhub-token");
    assertThat(build.toString()).doesNotContain("secrets.");
  }

  @Test
  @DisplayName("Should hand the preview image artifact to a separate publishing job")
  void shouldHandPreviewImageArtifactToSeparatePublishingJob() throws IOException {
    var workflow = yaml(".github/workflows/preview.yml");
    var jobs = map(workflow.get("jobs"));

    assertThat(jobs).containsKey("publish_preview");

    var build = map(jobs.get("build_preview"));
    var publish = map(jobs.get("publish_preview"));
    var buildSteps = listOfMaps(build.get("steps"));
    var publishSteps = listOfMaps(publish.get("steps"));

    assertThat(publish).containsEntry("needs", "build_preview");
    assertThat(buildSteps.stream().map(step -> step.get("name")))
        .containsSubsequence("Archive preview image", "Upload preview image");
    assertThat(publishSteps.stream().map(step -> step.get("name")))
        .contains("Download preview image");
  }

  @Test
  @DisplayName("Should keep PR-controlled code out of the privileged publishing job")
  void shouldKeepPrControlledCodeOutOfPrivilegedPublishingJob() throws IOException {
    var workflow = yaml(".github/workflows/preview.yml");
    var publish = map(map(workflow.get("jobs")).get("publish_preview"));

    assertThat(publish).containsKey("permissions");
    assertThat(map(publish.get("permissions")))
        .containsOnlyKeys("pull-requests")
        .containsEntry("pull-requests", "write");
    assertThat(listOfMaps(publish.get("steps")))
        .noneMatch(
            step -> {
              var action = String.valueOf(step.getOrDefault("uses", ""));
              return action.startsWith("./") || action.startsWith("actions/checkout@");
            });
  }

  @Test
  @DisplayName("Should inspect the preview artifact before authenticating and publishing")
  void shouldInspectPreviewArtifactBeforeAuthenticatingAndPublishing() throws IOException {
    var workflow = yaml(".github/workflows/preview.yml");
    var publish = map(map(workflow.get("jobs")).get("publish_preview"));
    var steps = listOfMaps(publish.get("steps"));

    assertThat(steps.stream().map(step -> step.get("name")))
        .containsSubsequence(
            "Download preview image",
            "Load preview image",
            "Login to Docker Hub",
            "Publish preview image");

    var load = stepNamed(steps, "Load preview image");
    var login = stepNamed(steps, "Login to Docker Hub");
    var publishImage = stepNamed(steps, "Publish preview image");

    assertThat((String) load.get("run"))
        .contains("docker image load", "docker image inspect")
        .doesNotContain("docker run");
    assertThat(load.toString()).doesNotContain("secrets.");
    assertThat(map(login.get("with")))
        .containsEntry("username", "${{ secrets.DOCKERHUB_USERNAME }}")
        .containsEntry("password", "${{ secrets.DOCKERHUB_TOKEN }}");
    assertThat(steps.stream().filter(step -> step.toString().contains("secrets.")).toList())
        .containsExactly(login);
    assertThat((String) publishImage.get("run")).contains("docker tag", "docker push");
    assertThat(publish.toString()).doesNotContain("docker run");
  }

  @Test
  @DisplayName("Should report successful publication from the privileged job")
  void shouldReportSuccessfulPublicationFromPrivilegedJob() throws IOException {
    var workflow = yaml(".github/workflows/preview.yml");
    var publish = map(map(workflow.get("jobs")).get("publish_preview"));
    var steps = listOfMaps(publish.get("steps"));

    assertThat(steps.stream().map(step -> step.get("name")))
        .contains("React to command", "Comment on PR");

    var comment = (String) map(stepNamed(steps, "Comment on PR").get("with")).get("script");
    assertThat(comment)
        .contains("context.issue.number")
        .doesNotContain("${{ github.event.issue.number }}");
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
