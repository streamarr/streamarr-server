package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.IntegrationTestFork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("CI Pipeline Workflow Tests")
class CiPipelineWorkflowTest {

  private static final String SONAR_ANALYSIS =
      "Analyze coverage and require the SonarCloud quality gate";

  @TempDir private Path temporaryDirectory;

  @ParameterizedTest
  @CsvSource({"false, true", "true, false", "'', false"})
  @DisplayName("Should allow a skipped package image only when changed paths need no packaging")
  void shouldAllowSkippedPackageImageOnlyWhenChangedPathsNeedNoPackaging(
      String packaging, boolean allowed) throws Exception {
    var allowedSkips = map(requiredJobs().get("with")).get("allowed-skips").toString();
    var expression =
        Pattern.compile("^\\$\\{\\{ (.+) && 'package_image' \\|\\| '' }}$").matcher(allowedSkips);
    assertThat(expression.matches()).as(allowedSkips).isTrue();
    var context = Map.of("needs.changes.outputs.packaging", packaging);

    var result = runBash("[[ " + substituteContext(expression.group(1), context) + " ]]");

    assertThat(result.exitCode()).as(result.output()).isEqualTo(allowed ? 0 : 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unit", "integration"})
  @DisplayName("Should select complementary Maven profiles for parallel application jobs")
  void shouldSelectComplementaryMavenProfilesForParallelApplicationJobs(String suite)
      throws Exception {
    var matrix = map(map(job("application").get("strategy")).get("matrix"));
    assertThat(matrix).containsEntry("suite", List.of("unit", "integration"));
    assertThat(job("analysis")).containsEntry("needs", "application");
    var capture = argumentCapturingMaven();
    var arguments =
        Map.of(
            "matrix.suite",
            suite,
            "matrix.suite == 'integration'",
            Boolean.toString(suite.equals("integration")));
    var command = render(step("application", "Build and test").get("run").toString(), arguments);

    var result = runBash(command.replace("./mvnw", "'" + capture + "'"));

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output().lines().toList())
        .containsExactly(
            "--batch-mode",
            "verify",
            "-Pci-" + suite,
            "-Dcheckstyle.skip=" + suite.equals("integration"));
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
    var xpath = XPathFactory.newInstance().newXPath();
    var profile = "/project/profiles/profile[id='ci-" + suite + "']";
    assertThat(xpath.evaluate(profile + "/properties/jacoco.aggregate.skip", pom))
        .isEqualTo("true");
    assertThat(xpath.evaluate(profile + "/properties/skipITs", pom))
        .isEqualTo(suite.equals("unit") ? "true" : "");
    var plugins = profile + "/build/plugins/plugin";
    assertThat(
            xpath.evaluate(
                plugins + "[artifactId='maven-surefire-plugin']/configuration/skipTests", pom))
        .isEqualTo(suite.equals("integration") ? "true" : "");
    assertThat(
            xpath.evaluate(
                plugins + "[artifactId='maven-failsafe-plugin']/configuration/skipTests", pom))
        .isEmpty();
  }

  @Test
  @DisplayName("Should run the integration tests in two numbered forks only when CI runs them")
  void shouldRunIntegrationTestsInTwoNumberedForksOnlyWhenCiRunsThem() throws Exception {
    var pom = pom();
    var xpath = XPathFactory.newInstance().newXPath();
    var failsafe = "/build/plugins/plugin[artifactId='maven-failsafe-plugin']/configuration";
    var ci = "/project/profiles/profile[id='ci-integration']" + failsafe;

    assertThat(xpath.evaluate(ci + "/forkCount", pom)).as("CI forks").isEqualTo("2");
    assertThat(xpath.evaluate(ci + "/reuseForks", pom))
        .as("each CI fork runs class after class")
        .isEqualTo("true");
    assertThat(
            xpath.evaluate(
                ci + "/systemPropertyVariables/" + IntegrationTestFork.NUMBER_PROPERTY, pom))
        .as("fork number placeholder that Maven leaves for Failsafe")
        .isEqualTo("$${surefire.forkNumber}");
    assertThat(xpath.evaluate("/project" + failsafe + "/forkCount", pom))
        .as("forks of a developer's build")
        .isEmpty();
  }

  @Test
  @DisplayName("Should export the checked-in worker pin when CI has no image override")
  void shouldExportCheckedInWorkerPinWhenCiHasNoImageOverride() throws Exception {
    var script = temporaryDirectory.resolve(".github/actions/require-worker-image.sh");
    Files.createDirectories(script.getParent());
    Files.copy(Path.of(".github/actions/require-worker-image.sh"), script);
    var expected =
        "STREAMARR_WORKER_IMAGE=streamarr/streamarr-transcode-worker:0.2.0-SNAPSHOT@sha256:"
            + "b".repeat(64)
            + "\n";
    Files.writeString(temporaryDirectory.resolve("worker-image.env"), expected);
    var environment = temporaryDirectory.resolve("github-env");

    var result =
        runBash(
            "unset STREAMARR_WORKER_IMAGE\nexport GITHUB_ENV='"
                + environment
                + "'\nbash '"
                + script
                + "'");

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(environment)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should accept an immutable worker image when running required CI tests")
  void shouldAcceptImmutableWorkerImageWhenRunningRequiredCiTests() throws Exception {
    var command = step("application", "Require standalone worker image").get("run").toString();

    var result =
        runBash(
            "export STREAMARR_WORKER_IMAGE=streamarr/streamarr-transcode-worker@sha256:"
                + "a".repeat(64)
                + "\n"
                + command);

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(temporaryDirectory.resolve("github-env")))
        .isEqualTo(
            "STREAMARR_WORKER_IMAGE=streamarr/streamarr-transcode-worker@sha256:"
                + "a".repeat(64)
                + "\n");
  }

  @ParameterizedTest
  @CsvSource({
    "streamarr/streamarr-transcode-worker:latest",
    "streamarr/streamarr-transcode-worker:1.2.3",
    "streamarr/streamarr-transcode-worker@sha256:abc123"
  })
  @DisplayName("Should reject an unpinned worker image when running required CI tests")
  void shouldRejectUnpinnedWorkerImageWhenRunningRequiredCiTests(String image) throws Exception {
    var command = step("application", "Require standalone worker image").get("run").toString();

    var result = runBash("export STREAMARR_WORKER_IMAGE=" + image + "\n" + command);

    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(result.output()).contains("immutable", "sha256");
  }

  @ParameterizedTest
  @CsvSource({
    "refs/heads/main, false, true",
    "refs/heads/main, true, true",
    "refs/pull/389/merge, false, false",
    "refs/pull/389/merge, true, true"
  })
  @DisplayName("Should build native images when main changes or pull request packaging changes")
  void shouldBuildNativeImagesWhenMainChangesOrPullRequestPackagingChanges(
      String ref, String packaging, boolean required) throws Exception {
    var condition = job("package_image").get("if").toString();
    var context = Map.of("github.ref", ref, "needs.changes.outputs.packaging", packaging);

    var result = runBash("[[ " + substituteContext(condition, context) + " ]]");

    assertThat(result.exitCode()).as(result.output()).isEqualTo(required ? 0 : 1);
  }

  @Test
  @DisplayName("Should require packaging for main when reporting changed paths to the build gate")
  void shouldRequirePackagingForMainWhenReportingChangedPathsToBuildGate() throws Exception {
    assertThat(map(job("changes").get("outputs")))
        .containsEntry(
            "packaging",
            "${{ github.ref == 'refs/heads/main' || steps.filter.outputs.packaging == 'true' }}");
  }

  @Test
  @DisplayName("Should preserve main builds when workflow runs overlap")
  void shouldPreserveMainBuildsWhenWorkflowRunsOverlap() throws Exception {
    try (var input = Files.newInputStream(Path.of(".github/workflows/ci.yml"))) {
      Map<String, Object> workflow = new Yaml().load(input);

      assertThat(map(workflow.get("concurrency")))
          .containsEntry("cancel-in-progress", "${{ github.ref != 'refs/heads/main' }}");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "push, refs/heads/main, true",
    "pull_request, refs/pull/389/merge, false",
    "workflow_dispatch, refs/heads/main, false"
  })
  @DisplayName("Should publish snapshots only when reviewed main CI succeeds")
  void shouldPublishSnapshotsOnlyWhenReviewedMainCiSucceeds(
      String event, String ref, boolean publish) throws Exception {
    assertThat(job("publish_snapshot"))
        .containsEntry("needs", List.of("changes", "build", "package_image"));
    var condition = job("publish_snapshot").get("if").toString();
    var context = Map.of("github.event_name", event, "github.ref", ref);

    var result = runBash("[[ " + substituteContext(condition, context) + " ]]");

    assertThat(result.exitCode()).as(result.output()).isEqualTo(publish ? 0 : 1);
    assertThat(job("publish_snapshot").get("uses").toString())
        .matches("streamarr/streamarr-workflows/.github/workflows/publish-image.yml@[a-f0-9]{40}");
    assertThat(map(job("publish_snapshot").get("with")))
        .containsEntry("image-repository", "streamarr/streamarr-server")
        .containsEntry("source-revision", "${{ github.sha }}")
        .containsEntry("version", "${{ needs.changes.outputs.version }}")
        .containsEntry("artifact-pattern", "server-image-*")
        .containsEntry("publication-kind", "snapshot");
    assertThat(map(job("publish_snapshot").get("secrets")))
        .containsOnly(
            Map.entry("dockerhub-username", "${{ secrets.DOCKERHUB_USERNAME }}"),
            Map.entry("dockerhub-token", "${{ secrets.DOCKERHUB_TOKEN }}"));
    assertThat(map(job("changes").get("outputs")))
        .containsEntry("version", "${{ steps.image.outputs.version }}");
  }

  @Test
  @DisplayName("Should aggregate every applicable CI result when verifying required build status")
  void shouldAggregateEveryApplicableCiResultWhenVerifyingRequiredBuildStatus() throws Exception {
    assertThat(job("package_image"))
        .containsEntry("needs", "changes")
        .containsEntry(
            "if", "github.ref == 'refs/heads/main' || needs.changes.outputs.packaging == 'true'");
    assertThat(job("build"))
        .containsEntry(
            "needs", List.of("changes", "contracts", "application", "analysis", "package_image"))
        .containsEntry("if", "${{ always() }}")
        .containsEntry("timeout-minutes", 5);
    assertThat(requiredJobs().get("uses").toString())
        .matches("streamarr/streamarr-workflows/.github/actions/require-jobs@[a-f0-9]{40}");
    assertThat(map(requiredJobs().get("with"))).containsEntry("jobs", "${{ toJSON(needs) }}");
  }

  @Test
  @DisplayName("Should verify contracts apart from the application build when checks run")
  void shouldVerifyContractsApartFromApplicationBuildWhenChecksRun() throws Exception {
    var contractChecks =
        List.of(
            "Validate Protobuf contract",
            "Detect breaking Protobuf changes",
            "Verify deployment image configuration",
            "Verify native image publication");

    assertThat(job("contracts")).doesNotContainKey("needs");
    assertThat(stepNames("contracts")).containsAll(contractChecks);
    assertThat(stepNames("application")).doesNotContainAnyElementsOf(contractChecks);
  }

  // A checkout in an always() job can hang, so the shared action is the build job's only step.
  private Map<String, Object> requiredJobs() throws Exception {
    var buildSteps = steps(job("build").get("steps"));
    assertThat(buildSteps).hasSize(1);

    return buildSteps.getFirst();
  }

  @ParameterizedTest
  @CsvSource({
    "pull_request, refs/pull/353/merge, streamarr/streamarr-server, maintainer, true",
    "pull_request, refs/pull/353/merge, contributor/streamarr-server, contributor, false",
    "pull_request, refs/pull/353/merge, streamarr/streamarr-server, dependabot[bot], false",
    "push, refs/heads/main, '', maintainer, true",
    "push, refs/heads/feature, '', maintainer, false",
    "workflow_dispatch, refs/heads/main, '', maintainer, true",
    "workflow_dispatch, refs/heads/feature, '', maintainer, false"
  })
  @DisplayName("Should scope Sonar analysis to trusted pull requests and main")
  void shouldScopeSonarAnalysisToTrustedPullRequestsAndMain(
      String event, String ref, String headRepository, String actor, boolean analyzed)
      throws Exception {
    var context =
        Map.of(
            "github.event.pull_request.head.repo.full_name", headRepository,
            "github.repository", "streamarr/streamarr-server",
            "github.event_name", event,
            "github.ref", ref,
            "github.actor", actor);
    for (var name : List.of("Cache SonarCloud packages", SONAR_ANALYSIS)) {
      var condition = step("analysis", name).get("if").toString();
      var result = runBash("[[ " + substituteContext(condition, context) + " ]]");

      assertThat(result.exitCode())
          .as("%s: event=%s ref=%s head=%s actor=%s", name, event, ref, headRepository, actor)
          .isEqualTo(analyzed ? 0 : 1);
    }
  }

  @Test
  @DisplayName("Should fail with a setup instruction when the analysis token is missing")
  void shouldFailWithSetupInstructionWhenAnalysisTokenIsMissing() throws Exception {
    var result = runBash(sonarAnalysisCommand(), Map.of("SONAR_TOKEN", ""));

    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(result.output())
        .contains("Grant this repository access to ORG_SONAR_TOKEN")
        .doesNotContain("sonar-maven-plugin");
  }

  @Test
  @DisplayName("Should wait for the quality gate when analyzing with a token")
  void shouldWaitForQualityGateWhenAnalyzingWithToken() throws Exception {
    var result = runBash(sonarAnalysisCommand(), Map.of("SONAR_TOKEN", "test-token"));

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output().lines().toList())
        .containsExactly(
            "--batch-mode",
            "org.sonarsource.scanner.maven:sonar-maven-plugin:sonar",
            "-Dsonar.qualitygate.wait=true");
  }

  @Test
  @DisplayName("Should expose the analysis token only to the analysis step")
  void shouldExposeAnalysisTokenOnlyToAnalysisStep() throws Exception {
    assertThat(job("analysis")).doesNotContainKey("env");
    assertThat(steps(job("analysis").get("steps")))
        .filteredOn(candidate -> candidate.containsKey("env"))
        .filteredOn(candidate -> map(candidate.get("env")).containsKey("SONAR_TOKEN"))
        .singleElement()
        .satisfies(
            analysis -> {
              assertThat(analysis).containsEntry("name", SONAR_ANALYSIS);
              assertThat(map(analysis.get("env")))
                  .containsEntry("SONAR_TOKEN", "${{ secrets.ORG_SONAR_TOKEN }}");
            });
  }

  private String sonarAnalysisCommand() throws Exception {
    return step("analysis", SONAR_ANALYSIS)
        .get("run")
        .toString()
        .replace("./mvnw", "'" + argumentCapturingMaven() + "'");
  }

  private Path argumentCapturingMaven() throws Exception {
    var capture = temporaryDirectory.resolve("mvnw");
    Files.writeString(capture, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
    assertThat(capture.toFile().setExecutable(true)).isTrue();

    return capture;
  }

  private static Document pom() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
  }

  private static String substituteContext(String expression, Map<String, String> context) {
    var result = expression;
    for (var entry : context.entrySet()) {
      result = result.replace(entry.getKey(), "'" + entry.getValue() + "'");
    }

    return result;
  }

  private Map<String, Object> job(String name) throws Exception {
    try (var input = Files.newInputStream(Path.of(".github/workflows/ci.yml"))) {
      Map<String, Object> workflow = new Yaml().load(input);
      return map(map(workflow.get("jobs")).get(name));
    }
  }

  private List<Object> stepNames(String job) throws Exception {
    return steps(job(job).get("steps")).stream().map(step -> step.get("name")).toList();
  }

  private Map<String, Object> step(String job, String name) throws Exception {
    return steps(job(job).get("steps")).stream()
        .filter(candidate -> name.equals(candidate.get("name")))
        .findFirst()
        .orElseThrow();
  }

  private static String render(String command, Map<String, String> context) {
    return Pattern.compile("\\$\\{\\{([^{}]*+)}}")
        .matcher(command)
        .replaceAll(match -> context.get(match.group(1).strip()));
  }

  private CommandResult runBash(String command) throws Exception {
    return runBash(command, Map.of());
  }

  private CommandResult runBash(String command, Map<String, String> environment) throws Exception {
    var output = temporaryDirectory.resolve("command.log");
    var builder =
        new ProcessBuilder("bash", "-e", "-c", command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().put("GITHUB_ENV", temporaryDirectory.resolve("github-env").toString());
    builder.environment().putAll(environment);
    var process = builder.start();
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("CI command completed").isTrue();
    return new CommandResult(process.exitValue(), Files.readString(output));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> steps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private record CommandResult(int exitCode, String output) {}
}
