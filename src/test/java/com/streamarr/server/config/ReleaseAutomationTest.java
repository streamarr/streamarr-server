package com.streamarr.server.config;

import static com.streamarr.server.config.ReleaseWorkflowFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Release Automation Tests")
class ReleaseAutomationTest {

  @ParameterizedTest
  @CsvSource({"true, 0", "false, 1", "null, 1"})
  @DisplayName("Should proceed only when repository auto-merge is available and enabled")
  void shouldProceedOnlyWhenRepositoryAutoMergeIsAvailableAndEnabled(
      String setting, int exitCode, @TempDir Path directory) throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.stub("gh", "printf '%s' \"$AUTO_MERGE\"\n");
    var command = fixture.step("release-please", "Verify repository auto-merge");
    command.environment().put("AUTO_MERGE", setting);
    var result = run(command);
    assertThat(result.exitCode()).as(result.output()).isEqualTo(exitCode);
    if (!"null".equals(setting)) return;

    assertThat(result.output()).contains("allow_auto_merge=null", "Check release App access");
  }

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = "42")
  @DisplayName("Should stop release preparation when merged release metadata remains pending")
  void shouldStopReleasePreparationWhenMergedReleaseMetadataRemainsPending(
      String pending, @TempDir Path directory) throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.stub("gh", "printf '%s' \"$PENDING_PR\"\n");
    var command = fixture.step("release-please", "Verify merged releases were processed");
    command.environment().put("PENDING_PR", pending);
    var result = run(command);
    assertThat(result.exitCode()).isEqualTo(pending.isEmpty() ? 0 : 1);
    if (pending.isEmpty()) return;

    assertThat(result.output())
        .contains("Unprocessed merged release PR #42", "restore its release title and body");
  }

  @Test
  @DisplayName("Should reject auto-merge when the snapshot PR has no valid head revision")
  void shouldRejectAutoMergeWhenSnapshotPrHasNoValidHeadRevision(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.stub(
        "gh",
        """
        if [ "$1" = api ]; then printf '%s' missing; exit 0; fi
        touch merged
        """);
    var command = fixture.step("release-please", "Queue snapshot auto-merge");
    command
        .environment()
        .put(
            "RELEASE_PR",
            """
        {"number":43,"title":"chore(main): release 0.0.12-SNAPSHOT","labels":["autorelease: snapshot"]}
        """);
    var result = run(command);
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release PR has no head revision");
    assertThat(directory.resolve("merged")).doesNotExist();
  }

  @ParameterizedTest
  @CsvSource({"pending, false", "snapshot, true"})
  @DisplayName("Should request auto-merge only when the action returns a snapshot PR")
  void shouldRequestAutoMergeOnlyWhenActionReturnsSnapshotPr(
      String label, boolean queued, @TempDir Path directory) throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.stub(
        "gh",
        """
        if [ "$1" = api ]; then printf '%s' "$HEAD_SHA"; exit 0; fi
        printf '%s\n' "$@" > "$MERGE_ARGS"
        """);
    var command = fixture.step("release-please", "Queue snapshot auto-merge");
    var title = "chore(main): release 0.0.12-SNAPSHOT";
    var head = "e".repeat(40);
    command
        .environment()
        .put(
            "RELEASE_PR",
            """
        {"number":43,"title":"%s","labels":["autorelease: %s"]}
        """
                .formatted(title, label));
    command.environment().put("HEAD_SHA", head);
    command.environment().put("MERGE_ARGS", directory.resolve("merge-args").toString());
    var result = run(command);
    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.exists(directory.resolve("merge-args"))).isEqualTo(queued);
    if (!queued) return;

    assertThat(Files.readAllLines(directory.resolve("merge-args")))
        .containsExactly(
            "pr",
            "merge",
            "43",
            "--repo",
            "streamarr/streamarr-server",
            "--auto",
            "--squash",
            "--match-head-commit",
            head,
            "--subject",
            title,
            "--body",
            "");
  }
}
