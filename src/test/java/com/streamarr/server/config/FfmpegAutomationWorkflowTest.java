package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("FFmpeg Automation Workflow Tests")
class FfmpegAutomationWorkflowTest {

  private static final String DEPENDENCY = "BtbN/FFmpeg-Builds";
  private static final String LOCK_BOT_EMAIL =
      "streamarr-ffmpeg-lock[bot]@users.noreply.github.com";

  @Test
  @DisplayName("Should isolate exact FFmpeg release updates for lock synchronization")
  void shouldIsolateExactFfmpegReleaseUpdatesForLockSynchronization() throws IOException {
    var releasePath = "buildpacks/ffmpeg/release";
    var releaseInput = Files.readString(Path.of(releasePath));
    var release = releaseInput.strip();
    var renovate = new ObjectMapper().readTree(Files.readString(Path.of("renovate.json")));
    var manager =
        nodes(renovate.path("customManagers"))
            .filter(candidate -> managesFile(candidate, releasePath))
            .findFirst()
            .orElseThrow();
    var matchStrings = strings(manager.path("matchStrings")).toList();
    var configuredPattern = Pattern.compile(matchStrings.getFirst());
    var ffmpegRule =
        nodes(renovate.path("packageRules"))
            .filter(rule -> strings(rule.path("matchDepNames")).anyMatch(DEPENDENCY::equals))
            .findFirst()
            .orElseThrow();

    assertThat(matchStrings).hasSize(1);
    assertThat(configuredPattern.matcher(releaseInput).matches()).isTrue();
    assertThat(configuredPattern.matcher(release).matches()).isTrue();
    assertThat(List.of("prefix" + release, release + "5", release + "\nextra"))
        .allSatisfy(input -> assertThat(configuredPattern.matcher(input).matches()).isFalse());
    assertThat(manager.path("datasourceTemplate").asText()).isEqualTo("github-releases");
    assertThat(manager.path("depNameTemplate").asText()).isEqualTo(DEPENDENCY);
    assertThat(ffmpegRule.path("groupName").asText()).isEqualTo("FFmpeg runtime");
    assertThat(ffmpegRule.path("automerge").isBoolean()).isTrue();
    assertThat(ffmpegRule.path("automerge").asBoolean()).isFalse();
    assertThat(strings(renovate.path("gitIgnoredAuthors"))).containsExactly(LOCK_BOT_EMAIL);
  }

  @Test
  @DisplayName("Should synchronize only canonical FFmpeg lock data from trusted workflow code")
  void shouldSynchronizeOnlyCanonicalFfmpegLockDataFromTrustedWorkflowCode() throws IOException {
    var workflowPath = ".github/workflows/sync-ffmpeg-lock.yml";
    var source = Files.readString(Path.of(workflowPath));
    var workflow = yaml(workflowPath);
    var job = map(map(workflow.get("jobs")).get("sync_ffmpeg_lock"));
    var steps = listOfMaps(job.get("steps"));
    var names = steps.stream().map(step -> step.get("name")).toList();
    var trustedCheckout = stepNamed(steps, "Check out trusted resolver");
    var proposedCheckout = stepNamed(steps, "Check out proposed Renovate head");
    var resolve = stepNamed(steps, "Resolve FFmpeg lock from trusted code");
    var prepare = stepNamed(steps, "Prepare synchronized lock");
    var verifyHead = stepNamed(steps, "Verify Renovate head is unchanged");
    var token = stepNamed(steps, "Mint lock bot token");
    var commit = stepNamed(steps, "Commit synchronized lock");
    var tokenIndex = names.indexOf("Mint lock bot token");

    assertThat(source).contains("pull_request_target:", "- 'buildpacks/ffmpeg/release'");
    assertThat(map(workflow.get("permissions"))).containsOnly(Map.entry("contents", "read"));
    assertThat((String) job.get("if"))
        .contains(
            "github.event.pull_request.user.login == 'renovate[bot]'",
            "github.event.pull_request.head.repo.full_name == github.repository",
            "startsWith(github.event.pull_request.head.ref, 'renovate/')");
    assertThat(map(trustedCheckout.get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.base.sha }}")
        .containsEntry("path", "trusted")
        .containsEntry("persist-credentials", false);
    assertThat(map(proposedCheckout.get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.head.sha }}")
        .containsEntry("path", "proposed")
        .containsEntry("persist-credentials", false);
    assertThat((String) resolve.get("run"))
        .contains(
            "git -C proposed show \"HEAD:buildpacks/ffmpeg/release\"",
            "trusted/buildpacks/ffmpeg/bin/update-lock",
            "--root \"${GITHUB_WORKSPACE}/trusted\"",
            "--release-file \"${release_file}\"")
        .doesNotContain("proposed/buildpacks/ffmpeg/bin/update-lock");
    assertThat((String) prepare.get("run"))
        .contains(
            "git -C proposed hash-object -w",
            "git -C proposed rev-parse \"HEAD:${lock_path}\"",
            "lock-blob=${lock_blob}",
            "changed=true");
    assertThat((String) verifyHead.get("run"))
        .contains(
            "git check-ref-format",
            "git -C proposed rev-parse HEAD",
            "gh api",
            "current_head_sha",
            "EXPECTED_HEAD_SHA");
    assertThat(names)
        .containsSubsequence(
            "Resolve FFmpeg lock from trusted code",
            "Prepare synchronized lock",
            "Verify Renovate head is unchanged",
            "Mint lock bot token",
            "Commit synchronized lock");
    assertThat(steps.subList(0, tokenIndex).toString()).doesNotContain("secrets.");
    assertThat((String) token.get("uses"))
        .isEqualTo("actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1");
    assertThat(map(token.get("with")))
        .containsEntry("client-id", "${{ secrets.FFMPEG_LOCK_APP_CLIENT_ID }}")
        .containsEntry("private-key", "${{ secrets.FFMPEG_LOCK_APP_PRIVATE_KEY }}")
        .containsEntry("permission-contents", "write");
    assertThat((String) commit.get("run"))
        .contains(
            "git update-index --add --cacheinfo",
            "git diff --cached --name-only",
            "git diff --cached --check",
            "git push origin \"HEAD:refs/heads/${HEAD_REF}\"")
        .contains("git config user.email '" + LOCK_BOT_EMAIL + "'");
  }

  @Test
  @DisplayName("Should verify proposed FFmpeg data with the trusted resolver when applicable")
  void shouldVerifyProposedFfmpegDataWithTrustedResolverWhenApplicable() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var lock = map(map(workflow.get("jobs")).get("ffmpeg_lock"));
    var steps = listOfMaps(lock.get("steps"));
    var trustedCheckout = stepNamed(steps, "Check out trusted resolver");
    var proposedCheckout = stepNamed(steps, "Check out proposed source");
    var offline = stepNamed(steps, "Validate FFmpeg lock offline");
    var upstream = stepNamed(steps, "Verify FFmpeg lock against upstream");

    assertThat(lock)
        .containsEntry("needs", "changes")
        .containsEntry("permissions", Map.of("contents", "read"));
    assertThat(map(trustedCheckout.get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.base.sha || github.sha }}")
        .containsEntry("path", "trusted")
        .containsEntry("persist-credentials", false);
    assertThat(map(proposedCheckout.get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.head.sha || github.sha }}")
        .containsEntry("path", "proposed")
        .containsEntry("persist-credentials", false);
    assertThat(offline).doesNotContainKeys("if", "env");
    assertThat((String) offline.get("run"))
        .isEqualTo("trusted/buildpacks/ffmpeg/bin/update-lock --root proposed --check");
    assertThat(upstream).containsEntry("if", "needs.changes.outputs.packaging == 'true'");
    assertThat((String) upstream.get("run"))
        .isEqualTo("trusted/buildpacks/ffmpeg/bin/update-lock --root proposed --verify-upstream");
    assertThat(map(upstream.get("env"))).containsEntry("GITHUB_TOKEN", "${{ github.token }}");
    assertThat(steps.toString()).doesNotContain("proposed/buildpacks/ffmpeg/bin/update-lock");
  }

  private static java.util.stream.Stream<JsonNode> nodes(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false);
  }

  private static java.util.stream.Stream<String> strings(JsonNode values) {
    return nodes(values).map(JsonNode::asText);
  }

  private static boolean managesFile(JsonNode manager, String path) {
    return strings(manager.path("managerFilePatterns"))
        .map(FfmpegAutomationWorkflowTest::renovatePattern)
        .anyMatch(pattern -> pattern.matcher(path).find());
  }

  private static Pattern renovatePattern(String value) {
    var lastSlash = value.lastIndexOf('/');
    return Pattern.compile(value.substring(1, lastSlash));
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
