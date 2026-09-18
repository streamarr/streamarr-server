package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Release Workflow Tests")
class ReleaseWorkflowTest {

  @Test
  @DisplayName("Should use the shared release workflow when maintaining releases")
  void shouldUseSharedReleaseWorkflowWhenMaintainingReleases() throws Exception {
    var release = map(jobs("release-please").get("release"));

    assertThat(release.get("uses").toString())
        .matches("streamarr/streamarr-workflows/.github/workflows/release-please.yml@[a-f0-9]{40}");
    assertThat(map(release.get("secrets")))
        .containsOnly(
            Map.entry("app-client-id", "${{ secrets.ORG_STREAMARR_RELEASE_CLIENT_ID }}"),
            Map.entry("app-private-key", "${{ secrets.ORG_STREAMARR_RELEASE_PRIVATE_KEY }}"));
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
  }

  @Test
  @DisplayName("Should validate the requested tag when publishing or retrying a release")
  void shouldValidateRequestedTagWhenPublishingOrRetryingRelease() throws Exception {
    var validation = map(jobs("publish-release").get("validate_release"));

    assertThat(validation.get("uses").toString())
        .matches(
            "streamarr/streamarr-workflows/.github/workflows/validate-release.yml@[a-f0-9]{40}");
    assertThat(map(validation.get("with"))).containsEntry("tag", "${{ inputs.tag || '' }}");
  }

  @Test
  @DisplayName("Should pin both image architectures to the validated release revision")
  void shouldPinBothImageArchitecturesToValidatedReleaseRevision() throws Exception {
    var build = map(jobs("publish-release").get("build_release_images"));
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
            .filter(step -> "$/.github/actions/pack-build".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();
    assertThat(map(pack.get("with")))
        .containsEntry("image-version", "${{ needs.validate_release.outputs.version }}");
    var nativePublish =
        steps.stream()
            .filter(step -> "Publish verified native image".equals(step.get("name")))
            .findFirst()
            .orElseThrow();
    assertThat(nativePublish).containsEntry("uses", "$/.github/actions/pack-build/publish");
    assertThat(map(nativePublish.get("with")))
        .containsEntry("image", "${{ steps.package.outputs.image }}")
        .containsEntry("architecture", "${{ matrix.architecture }}");
    var upload =
        steps.stream()
            .filter(step -> "Upload verified native image".equals(step.get("name")))
            .findFirst()
            .orElseThrow();
    assertThat(map(upload.get("with")))
        .containsEntry("name", "server-release-image-${{ matrix.architecture }}")
        .containsEntry("path", "${{ matrix.architecture }}-image.json")
        .containsEntry("if-no-files-found", "error");
  }

  @Test
  @DisplayName("Should publish validated native receipts when both release architectures succeed")
  void shouldPublishValidatedNativeReceiptsWhenBothReleaseArchitecturesSucceed() throws Exception {
    var publish = map(jobs("publish-release").get("publish_release"));

    assertThat(publish).containsEntry("needs", List.of("validate_release", "build_release_images"));
    assertThat(publish.get("uses").toString())
        .matches("streamarr/streamarr-workflows/.github/workflows/publish-image.yml@[a-f0-9]{40}");
    assertThat(map(publish.get("with")))
        .containsEntry("image-repository", "streamarr/streamarr-server")
        .containsEntry("source-revision", "${{ needs.validate_release.outputs.revision }}")
        .containsEntry("version", "${{ needs.validate_release.outputs.version }}")
        .containsEntry("artifact-pattern", "server-release-image-*")
        .containsEntry("publication-kind", "release");
    assertThat(map(publish.get("secrets")))
        .containsOnly(
            Map.entry("dockerhub-username", "${{ secrets.DOCKERHUB_USERNAME }}"),
            Map.entry("dockerhub-token", "${{ secrets.DOCKERHUB_TOKEN }}"));
  }

  private static Map<String, Object> jobs(String name) throws Exception {
    Map<String, Object> workflow =
        new Yaml().load(Files.readString(Path.of(".github/workflows/" + name + ".yml")));
    return map(workflow.get("jobs"));
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
