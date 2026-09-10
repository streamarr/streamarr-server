package com.streamarr.server.config;

import static com.streamarr.server.config.ReleaseWorkflowFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Release Publisher Tests")
class ReleasePublisherTest {

  @TempDir Path directory;
  private ReleaseWorkflowFixture fixture;

  @BeforeEach
  void prepareTaggedRepository() throws Exception {
    fixture = new ReleaseWorkflowFixture(directory);
    fixture.git("init", "--quiet", "--initial-branch=main");
    fixture.git("commit", "--quiet", "--allow-empty", "-m", "fixture");
    fixture.git("tag", "v1.2.3");
    fixture.git("update-ref", "refs/remotes/origin/main", "HEAD");
    fixture.stub("mvnw", "printf '%s' \"${POM_VERSION:-1.2.3}\"\n");
    fixture.stub("gh", "printf '%s' \"${RELEASE_DRAFT:-false}\"\n");
  }

  @Test
  @DisplayName("Should reject a different checkout when the release tag names another revision")
  void shouldRejectDifferentCheckoutWhenReleaseTagNamesAnotherRevision() throws Exception {
    fixture.git("commit", "--quiet", "--allow-empty", "-m", "later revision");
    var result = run(validation());
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release tag does not match the checked-out revision");
    assertThat(directory.resolve("outputs")).doesNotExist();
  }

  @Test
  @DisplayName("Should reject an unmerged release when the tagged commit is outside main")
  void shouldRejectUnmergedReleaseWhenTaggedCommitIsOutsideMain() throws Exception {
    fixture.git("commit", "--quiet", "--allow-empty", "-m", "unmerged revision");
    fixture.git("tag", "--force", "v1.2.3");
    var result = run(validation());
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release revision is not an ancestor of main");
    assertThat(directory.resolve("outputs")).doesNotExist();
  }

  @Test
  @DisplayName("Should export version and revision when the published release matches Maven")
  void shouldExportVersionAndRevisionWhenPublishedReleaseMatchesMaven() throws Exception {
    var result = run(validation());
    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(directory.resolve("outputs")))
        .isEqualTo("version=1.2.3\nrevision=" + fixture.git("rev-parse", "HEAD") + "\n");
  }

  @Test
  @DisplayName("Should reject publishing when the GitHub release is still a draft")
  void shouldRejectPublishingWhenGitHubReleaseIsStillDraft() throws Exception {
    var command = validation();
    command.environment().put("RELEASE_DRAFT", "true");
    var result = run(command);
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Publish the GitHub release before publishing images");
    assertThat(directory.resolve("outputs")).doesNotExist();
  }

  @Test
  @DisplayName("Should reject version drift when the Maven version differs from the release tag")
  void shouldRejectVersionDriftWhenMavenVersionDiffersFromReleaseTag() throws Exception {
    var command = validation();
    command.environment().put("POM_VERSION", "0.0.1-SNAPSHOT");
    var result = run(command);
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release tag and Maven version must agree");
    assertThat(directory.resolve("outputs")).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"01.2.3", "1.2", "1.2.3-SNAPSHOT", "1.2.3-rc.1", "1.2.3+build", "1.2.3\nextra"})
  @DisplayName("Should reject the release when its tag is not stable SemVer")
  void shouldRejectReleaseWhenTagIsNotStableSemver(String version) throws Exception {
    fixture.stub("git", "if [ \"$1\" = merge-base ]; then exit 0; fi\nprintf '%s' revision\n");
    var command = validation();
    command.environment().put("RELEASE_TAG", "v" + version);
    command.environment().put("POM_VERSION", version);
    var result = run(command);
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release tag must be stable SemVer");
    assertThat(directory.resolve("outputs")).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 23})
  @DisplayName("Should preserve inspection status when Docker emits both image platforms")
  void shouldPreserveInspectionStatusWhenDockerEmitsBothImagePlatforms(int status)
      throws Exception {
    fixture.stub(
        "docker",
        """
        if [ "$3" = create ]; then exit 0; fi
        printf '%s' '{"manifests":[{"platform":{"os":"linux","architecture":"amd64"}},{"platform":{"os":"linux","architecture":"arm64"}}]}'
        exit "$INSPECTION_STATUS"
        """);
    var command = fixture.step("publish-release", "Publish immutable multi-architecture image");
    command.environment().put("IMAGE_VERSION", "1.2.3");
    command.environment().put("INSPECTION_STATUS", String.valueOf(status));
    assertThat(run(command).exitCode()).isEqualTo(status);
  }

  private ProcessBuilder validation() throws Exception {
    var command = fixture.step("publish-release", "Verify tagged Maven version");
    command.environment().put("RELEASE_TAG", "v1.2.3");
    return command;
  }
}
