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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("FFmpeg Automation Workflow Tests")
class FfmpegAutomationWorkflowTest {

  private static final String DEPENDENCY = "jellyfin/jellyfin-ffmpeg";
  private static final String LOCK_BOT_EMAIL =
      "315986519+streamarr-ffmpeg-lock[bot]@users.noreply.github.com";

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
    assertThat(List.of("prefix" + release, release + "-rc1", release + "\nextra"))
        .allSatisfy(input -> assertThat(configuredPattern.matcher(input).matches()).isFalse());
    assertThat(manager.path("datasourceTemplate").asText()).isEqualTo("github-releases");
    assertThat(manager.path("depNameTemplate").asText()).isEqualTo(DEPENDENCY);
    var versionPattern = Pattern.compile(manager.path("versioningTemplate").asText().substring(6));
    var futureBuild = versionPattern.matcher("v8.1.2-10");
    assertThat(futureBuild.matches()).isTrue();
    assertThat(futureBuild.group("major")).isEqualTo("8");
    assertThat(futureBuild.group("minor")).isEqualTo("1");
    assertThat(futureBuild.group("patch")).isEqualTo("2");
    assertThat(futureBuild.group("build")).isEqualTo("10");
    assertThat(versionPattern.matcher("v8.1.2-4-rc1").matches()).isFalse();
    assertThat(ffmpegRule.path("groupName").asText()).isEqualTo("FFmpeg runtime");
    assertThat(ffmpegRule.path("automerge").isBoolean()).isTrue();
    assertThat(ffmpegRule.path("automerge").asBoolean()).isFalse();
    assertThat(strings(renovate.path("gitIgnoredAuthors")))
        .contains(LOCK_BOT_EMAIL, "streamarr-ffmpeg-lock[bot]@users.noreply.github.com");
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
            "createCommitOnBranch",
            "expectedHeadOid: $expectedHead",
            "git cat-file blob",
            "buildpacks/ffmpeg/ffmpeg.lock",
            "gh api graphql")
        .doesNotContain("git commit", "git push", "git config", "proposed/buildpacks/");
    assertThat(map(commit.get("env")))
        .containsEntry("EXPECTED_HEAD_SHA", "${{ github.event.pull_request.head.sha }}")
        .containsEntry("GH_TOKEN", "${{ steps.lock_bot.outputs.token }}");
  }

  @Test
  @DisplayName("Should test proposed FFmpeg resolver changes with read-only PR credentials")
  void shouldTestProposedFfmpegResolverChangesWithReadOnlyPrCredentials() throws IOException {
    var source = Files.readString(Path.of(".github/workflows/ci.yml"));
    var workflow = yaml(".github/workflows/ci.yml");
    var lock = map(map(workflow.get("jobs")).get("ffmpeg_lock"));
    var steps = listOfMaps(lock.get("steps"));
    var checkout = stepNamed(steps, "Check out source");
    var offline = stepNamed(steps, "Validate FFmpeg lock offline");

    assertThat(source).contains("pull_request:").doesNotContain("pull_request_target:");
    assertThat(lock).containsEntry("permissions", Map.of("contents", "read"));
    assertThat(map(checkout.get("with")))
        .containsEntry("persist-credentials", false)
        .doesNotContainKeys("ref");
    assertThat((String) offline.get("run")).isEqualTo("buildpacks/ffmpeg/bin/update-lock --check");
    assertThat(steps.toString()).doesNotContain("secrets.");
  }

  @Test
  @DisplayName("Should smoke test the locked FFmpeg runtime on application and packaging runners")
  void shouldSmokeTestLockedFfmpegRuntimeOnApplicationAndPackagingRunners() throws IOException {
    var jobs = map(yaml(".github/workflows/ci.yml").get("jobs"));

    for (var jobName : List.of("application", "package_image")) {
      var steps = listOfMaps(map(jobs.get(jobName)).get("steps"));
      assertThat(steps.stream().map(step -> step.get("name")))
          .containsSubsequence("Install locked FFmpeg", "Run HLS smoke tests");
      assertThat(stepNamed(steps, "Install locked FFmpeg"))
          .containsEntry("uses", "./.github/actions/setup-ffmpeg")
          .containsEntry("timeout-minutes", 10);
      assertThat((String) stepNamed(steps, "Run HLS smoke tests").get("run"))
          .contains("-Dgroups=SmokeTest", "-Dsurefire.excludedGroups=");
      assertThat(steps.toString()).doesNotContain("apt-get install", "apt-mirrors");
    }

    var actionRuns = map(yaml(".github/actions/setup-ffmpeg/action.yml").get("runs"));
    var install = stepNamed(listOfMaps(actionRuns.get("steps")), "Install locked FFmpeg");
    assertThat(actionRuns).containsEntry("using", "composite");
    assertThat(install).containsEntry("shell", "bash");
    assertThat((String) install.get("run"))
        .contains("CNB_TARGET_ARCH=", "uname -m", "buildpacks/ffmpeg/bin/build", "GITHUB_PATH");
  }

  private static Stream<JsonNode> nodes(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false);
  }

  private static Stream<String> strings(JsonNode values) {
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
