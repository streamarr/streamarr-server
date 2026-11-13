package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.support.IntegrationTestShardFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("CI Pipeline Workflow Tests")
class CiPipelineWorkflowTest {

  private static final String SONAR_ANALYSIS =
      "Analyze coverage and require the SonarCloud quality gate";
  private static final String SMOKE_TESTS = "Run HLS smoke tests";
  private static final String PROMETHEUS_COMPOSE_INPUTS =
      "Detect changes to the Prometheus Compose test's inputs";
  private static final String WITHOUT_PROMETHEUS_COMPOSE_TEST = "-Dgroups=!PrometheusComposeTest";
  private static final String NOT_A_PROMETHEUS_COMPOSE_INPUT =
      "src/main/java/com/streamarr/server/services/library/LibraryManagementService.java";
  private static final String PULL_REQUEST = "refs/pull/389/merge";
  private static final String MAIN = "refs/heads/main";
  private static final String CI_INTEGRATION = "/project/profiles/profile[id='ci-integration']";
  private static final Predicate<String> FORMATTING_OR_STYLE_PROPERTY =
      Pattern.compile("-D(checkstyle|spotless)\\..*").asMatchPredicate();
  private static final Predicate<String> TEST_GROUP_SELECTION =
      Pattern.compile("-Dgroups=.*").asMatchPredicate();

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

  @Test
  @DisplayName("Should run the unit tests and every integration shard when application jobs run")
  void shouldRunTheUnitTestsAndEveryIntegrationShardWhenApplicationJobsRun() throws Exception {
    var shards = Integer.parseInt(pomValue(CI_INTEGRATION + "/properties/ci.integration.shards"));
    var jobs = new ArrayList<Map<String, Object>>(List.of(Map.of("suite", "unit")));
    IntStream.rangeClosed(1, shards)
        .forEach(shard -> jobs.add(Map.of("suite", "integration", "shard", shard)));

    assertThat(shards).as("integration shards").isGreaterThan(1);
    assertThat(map(job("application").get("strategy")))
        .containsEntry("fail-fast", false)
        .containsEntry("matrix", Map.of("include", jobs));
    assertThat(job("application"))
        .containsEntry(
            "name",
            "Build and test application (${{ matrix.suite }}"
                + "${{ matrix.shard && format(', shard {0}', matrix.shard) }})");
    assertThat(job("analysis")).containsEntry("needs", "application");
  }

  @ParameterizedTest
  @CsvSource({
    "unit, ''",
    "integration, -Dci.integration.shard=1",
    "integration, -Dci.integration.shard=2"
  })
  @DisplayName("Should select complementary Maven profiles when application jobs run in parallel")
  void shouldSelectComplementaryMavenProfilesWhenApplicationJobsRunInParallel(
      String suite, String shardArgument) throws Exception {
    var arguments = buildAndTestMavenArguments(suite, shardArgument);

    assertThat(arguments)
        .filteredOn(FORMATTING_OR_STYLE_PROPERTY.negate())
        .as("%s job's Maven arguments other than Checkstyle and Spotless properties", suite)
        .containsExactlyElementsOf(
            Stream.of("--batch-mode", "verify", "-Pci-" + suite, shardArgument)
                .filter(Predicate.not(String::isEmpty))
                .toList());
    var profile = "/project/profiles/profile[id='ci-" + suite + "']";
    assertThat(pomValue(profile + "/properties/jacoco.aggregate.skip")).isEqualTo("true");
    assertThat(pomValue(profile + "/properties/skipITs"))
        .isEqualTo(suite.equals("unit") ? "true" : "");
    var plugins = profile + "/build/plugins/plugin";
    assertThat(pomValue(plugins + "[artifactId='maven-surefire-plugin']/configuration/skipTests"))
        .isEqualTo(suite.equals("integration") ? "true" : "");
    assertThat(pomValue(plugins + "[artifactId='maven-failsafe-plugin']/configuration/skipTests"))
        .isEmpty();
  }

  @Test
  @DisplayName("Should hand each integration shard its shard and coverage file when CI runs them")
  void shouldHandEachIntegrationShardItsShardAndCoverageFileWhenCiRunsThem() throws Exception {
    var failsafe =
        CI_INTEGRATION + "/build/plugins/plugin[artifactId='maven-failsafe-plugin']/configuration";
    var jacoco =
        "/project/build/plugins/plugin[artifactId='jacoco-maven-plugin']/executions/execution";

    assertThat(
            pomValue(failsafe + "/systemPropertyVariables/" + IntegrationTestShardFilter.PROPERTY))
        .isEqualTo("${ci.integration.shard}/${ci.integration.shards}");
    assertThat(pomValue(CI_INTEGRATION + "/properties/integration.coverage.file"))
        .isEqualTo(
            "${project.build.directory}/jacoco-output/"
                + "jacoco-integration-${ci.integration.shard}-tests.exec");
    assertThat(pomValue(jacoco + "[id='before-integration-test-execution']/configuration/destFile"))
        .isEqualTo("${integration.coverage.file}");
    assertThat(pomValue(jacoco + "[id='after-integration-test-execution']/configuration/dataFile"))
        .isEqualTo("${integration.coverage.file}");
    assertThat(pomValue("/project/properties/integration.coverage.file"))
        .as("a developer build's integration coverage file")
        .isEqualTo("${project.build.directory}/jacoco-output/jacoco-integration-tests.exec");
    assertThat(
            pomValue(
                "count(//*[name()='"
                    + IntegrationTestShardFilter.PROPERTY
                    + "'][not(ancestor::profile)])"))
        .as("shard selections in a developer build")
        .isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "2"})
  @DisplayName("Should deliver each shard's reports and coverage to the analysis when shards run")
  void shouldDeliverEachShardsReportsAndCoverageToTheAnalysisWhenShardsRun(String shard)
      throws Exception {
    var upload = step("application", "Upload integration coverage");
    var context = Map.of("matrix.shard", shard);
    var coverage = "jacoco-integration-" + shard + "-tests.exec";
    var download = map(step("analysis", "Download test results").get("with"));
    var merged =
        pomValues(
            "/project/build/plugins/plugin[artifactId='jacoco-maven-plugin']/executions"
                + "/execution[id='merge-unit-and-integration']/configuration/fileSets/fileSet"
                + "/includes/include");

    var artifact = render(map(upload.get("with")).get("name").toString(), context);
    var paths = render(map(upload.get("with")).get("path").toString(), context).lines().toList();

    assertThat(upload).containsEntry("if", "matrix.suite == 'integration'");
    assertThat(artifact).isEqualTo("application-tests-integration-" + shard);
    assertThat(paths)
        .containsExactly(
            "target/failsafe-reports/",
            "!target/failsafe-reports/failsafe-summary.xml",
            "target/jacoco-output/" + coverage,
            "target/ci-integration-" + shard + "-classes.sha256");
    assertThat(download).containsEntry("path", "target").containsEntry("merge-multiple", true);
    assertThat(glob(download.get("pattern").toString()).matches(Path.of(artifact)))
        .as("analysis downloads %s", artifact)
        .isTrue();
    assertThat(merged)
        .as("coverage files the analysis merges")
        .anyMatch(include -> glob(include).matches(Path.of(coverage)));
  }

  @ParameterizedTest
  @CsvSource({
    "unit, '', false",
    "integration, -Dci.integration.shard=1, true",
    "integration, -Dci.integration.shard=2, true"
  })
  @DisplayName(
      "Should check formatting and style only in the unit job when every job builds one commit")
  void shouldCheckFormattingAndStyleOnlyInUnitJobWhenEveryJobBuildsOneCommit(
      String suite, String shardArgument, boolean skipped) throws Exception {
    var arguments = buildAndTestMavenArguments(suite, shardArgument);

    assertThat(arguments)
        .filteredOn(FORMATTING_OR_STYLE_PROPERTY)
        .as("%s job's Checkstyle and Spotless properties", suite)
        .containsExactlyInAnyOrder(
            "-Dcheckstyle.skip=" + skipped, "-Dspotless.check.skip=" + skipped);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "2"})
  @DisplayName(
      "Should exclude the Prometheus Compose test when a pull request changes none of its inputs")
  void shouldExcludeThePrometheusComposeTestWhenAPullRequestChangesNoneOfItsInputs(String shard)
      throws Exception {
    var job =
        integrationJob(shard)
            .ref(PULL_REQUEST)
            .changedFiles(List.of(NOT_A_PROMETHEUS_COMPOSE_INPUT))
            .build();

    var arguments = buildAndTestMavenArguments(job);

    assertThat(arguments)
        .filteredOn(TEST_GROUP_SELECTION)
        .as("test group selections")
        .containsExactly(WITHOUT_PROMETHEUS_COMPOSE_TEST);
  }

  @Test
  @DisplayName(
      "Should select no unit test groups when a pull request changes no Prometheus Compose input")
  void shouldSelectNoUnitTestGroupsWhenAPullRequestChangesNoPrometheusComposeInput()
      throws Exception {
    var job =
        ApplicationJob.builder()
            .suite("unit")
            .shardArgument("")
            .ref(PULL_REQUEST)
            .changedFiles(List.of(NOT_A_PROMETHEUS_COMPOSE_INPUT))
            .build();

    var arguments = buildAndTestMavenArguments(job);

    assertThat(arguments).filteredOn(TEST_GROUP_SELECTION).as("test group selections").isEmpty();
  }

  @Test
  @DisplayName(
      "Should detect Prometheus Compose input changes with the pinned paths filter when jobs run")
  void shouldDetectPrometheusComposeInputChangesWithThePinnedPathsFilterWhenJobsRun()
      throws Exception {
    assertThat(step("application", PROMETHEUS_COMPOSE_INPUTS).get("uses"))
        .as("paths filter of the Prometheus Compose test's inputs")
        .isEqualTo(step("changes", "Filter changed paths").get("uses"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".github/workflows/ci.yml",
        "docker-compose.yml",
        "pom.xml",
        "src/main/resources/application.yml",
        "src/main/resources/application-observability.yml",
        "src/test/resources/application-test.yml",
        "src/main/java/com/streamarr/server/config/observability/OtlpMetricsEndpointConfiguration.java",
        "src/main/java/com/streamarr/server/services/library/ScanProbesWaiting.java",
        "src/test/java/com/streamarr/server/config/observability/ComposeMetricsExportIT.java",
        "src/test/java/com/streamarr/server/config/observability/ComposeFile.java"
      })
  @DisplayName(
      "Should run the Prometheus Compose test when a pull request changes one of its inputs")
  void shouldRunThePrometheusComposeTestWhenAPullRequestChangesOneOfItsInputs(String input)
      throws Exception {
    for (var shard : List.of("1", "2")) {
      var job =
          integrationJob(shard)
              .ref(PULL_REQUEST)
              .changedFiles(List.of(NOT_A_PROMETHEUS_COMPOSE_INPUT, input))
              .build();

      var arguments = buildAndTestMavenArguments(job);

      assertThat(arguments)
          .filteredOn(TEST_GROUP_SELECTION)
          .as("shard %s's test group selections", shard)
          .isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "2"})
  @DisplayName("Should run the Prometheus Compose test when main builds")
  void shouldRunThePrometheusComposeTestWhenMainBuilds(String shard) throws Exception {
    var job =
        integrationJob(shard)
            .ref(MAIN)
            .changedFiles(List.of(NOT_A_PROMETHEUS_COMPOSE_INPUT))
            .build();

    var arguments = buildAndTestMavenArguments(job);

    assertThat(arguments).filteredOn(TEST_GROUP_SELECTION).as("test group selections").isEmpty();
  }

  @Test
  @DisplayName("Should run the Prometheus Compose test when a developer build verifies")
  void shouldRunThePrometheusComposeTestWhenADeveloperBuildVerifies() throws Exception {
    assertThat(Files.readAllLines(Path.of("pom.xml")))
        .filteredOn(line -> line.contains("PrometheusComposeTest"))
        .as("Maven configuration that names the Prometheus Compose test's tag")
        .isEmpty();
  }

  @Test
  @DisplayName("Should name checked-in files when listing the Prometheus Compose test's inputs")
  void shouldNameCheckedInFilesWhenListingThePrometheusComposeTestsInputs() throws Exception {
    var inputs = prometheusComposeInputs();

    assertThat(inputs).as("Prometheus Compose test inputs").isNotEmpty();
    assertThat(inputs)
        .filteredOn(Predicate.not(CiPipelineWorkflowTest::matchesACheckedInFile))
        .as("input patterns that match no checked-in file")
        .isEmpty();
  }

  private static ApplicationJob.ApplicationJobBuilder integrationJob(String shard) {
    return ApplicationJob.builder()
        .suite("integration")
        .shardArgument("-Dci.integration.shard=" + shard);
  }

  // A main build runs every integration test, so these are each job's complete arguments.
  private List<String> buildAndTestMavenArguments(String suite, String shardArgument)
      throws Exception {
    return buildAndTestMavenArguments(
        ApplicationJob.builder()
            .suite(suite)
            .shardArgument(shardArgument)
            .ref(MAIN)
            .changedFiles(List.of())
            .build());
  }

  private List<String> buildAndTestMavenArguments(ApplicationJob job) throws Exception {
    var context =
        Map.of(
            "matrix.suite",
            job.suite(),
            "matrix.suite == 'integration'",
            Boolean.toString(job.suite().equals("integration")),
            "matrix.shard && format('-Dci.integration.shard={0}', matrix.shard)",
            job.shardArgument());
    var detection = step("application", PROMETHEUS_COMPOSE_INPUTS);
    var outputs =
        Map.of(
            "steps." + detection.get("id") + ".outputs.changed",
            prometheusComposeInputsChanged(job));
    var run = step("application", "Build and test").get("run").toString();
    var command = render(renderConditionalArgument(run, outputs), context);

    var result = runBash(command.replace("./mvnw", "'" + argumentCapturingMaven() + "'"));

    assertThat(result.exitCode()).as(result.output()).isZero();
    return result.output().lines().toList();
  }

  // GitHub leaves the outputs of a skipped step empty. For these patterns the paths filter matches
  // as Java's glob does: * within one folder and ** across folders.
  private String prometheusComposeInputsChanged(ApplicationJob job) throws Exception {
    var condition = step("application", PROMETHEUS_COMPOSE_INPUTS).get("if").toString();
    if (!holds(condition, Map.of("matrix.suite", job.suite(), "github.ref", job.ref()))) {
      return "";
    }

    var inputs = prometheusComposeInputs().stream().map(CiPipelineWorkflowTest::glob).toList();
    var changed =
        job.changedFiles().stream()
            .map(Path::of)
            .anyMatch(file -> inputs.stream().anyMatch(input -> input.matches(file)));
    return Boolean.toString(changed);
  }

  private List<String> prometheusComposeInputs() throws Exception {
    var detection = step("application", PROMETHEUS_COMPOSE_INPUTS);
    Map<String, Object> filters =
        new Yaml().load(map(detection.get("with")).get("filters").toString());

    return ((List<?>) filters.get("changed")).stream().map(Object::toString).toList();
  }

  private static boolean matchesACheckedInFile(String pattern) {
    var wildcard = pattern.indexOf('*');
    if (wildcard < 0) {
      return Files.isRegularFile(Path.of(pattern));
    }

    var directory = Path.of(pattern.substring(0, pattern.lastIndexOf('/', wildcard)));
    if (!Files.isDirectory(directory)) {
      return false;
    }

    try (var files = Files.walk(directory)) {
      return files.anyMatch(glob(pattern)::matches);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // GitHub renders ${{ condition && 'argument' || '' }} as the argument while the condition holds.
  private String renderConditionalArgument(String command, Map<String, String> context)
      throws Exception {
    var conditional =
        Pattern.compile("\\$\\{\\{ ([^{}]+) && '([^']+)' \\|\\| '' }}").matcher(command);
    assertThat(conditional.find()).as("conditional argument in %s", command).isTrue();
    var argument = "";
    if (holds(conditional.group(1), context)) {
      argument = conditional.group(2);
    }

    return command.substring(0, conditional.start())
        + argument
        + command.substring(conditional.end());
  }

  private boolean holds(String condition, Map<String, String> context) throws Exception {
    var result = runBash("[[ " + substituteContext(condition, context) + " ]]");
    assertThat(result.exitCode()).as("%s: %s", condition, result.output()).isIn(0, 1);

    return result.exitCode() == 0;
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
            "needs",
            List.of("changes", "contracts", "application", "smoke", "analysis", "package_image"))
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

  @Test
  @DisplayName("Should start the HLS smoke job like the application jobs when checks run")
  void shouldStartHlsSmokeJobLikeApplicationJobsWhenChecksRun() throws Exception {
    assertThat(job("smoke"))
        .as("smoke job keys: no needs, if or continue-on-error")
        .containsOnlyKeys("name", "runs-on", "steps")
        .as("runner")
        .containsEntry("runs-on", job("application").get("runs-on"));
    assertThat(steps(job("smoke").get("steps")).subList(0, 3))
        .as("checkout, worker image pin and JDK setup")
        .isEqualTo(steps(job("application").get("steps")).subList(0, 3));
  }

  @Test
  @DisplayName("Should run every HLS smoke test when the smoke job runs")
  void shouldRunEveryHlsSmokeTestWhenSmokeJobRuns() throws Exception {
    var smokeTests = steps(job("smoke").get("steps")).getLast();
    assertThat(smokeTests)
        .containsEntry("name", SMOKE_TESTS)
        .as("smoke step keys: no if or continue-on-error")
        .containsOnlyKeys("name", "run");
    var command =
        smokeTests.get("run").toString().replace("./mvnw", "'" + argumentCapturingMaven() + "'");

    var result = runBash(command);

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output().lines().toList())
        .containsExactly(
            "--batch-mode",
            "test",
            "-Dsurefire.excludedGroups=",
            "-Dgroups=SmokeTest",
            "-Djacoco.skip=true",
            "-Dcheckstyle.skip=true",
            "-Dspotless.check.skip=true");
  }

  @Test
  @DisplayName("Should leave the HLS smoke tests out of the application build when checks run")
  void shouldLeaveHlsSmokeTestsOutOfApplicationBuildWhenChecksRun() throws Exception {
    assertThat(steps(job("application").get("steps")))
        .extracting(candidate -> String.valueOf(candidate.get("run")))
        .as("application steps that run smoke tests")
        .noneMatch(run -> run.contains("SmokeTest"));
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

  private static String pomValue(String expression) throws Exception {
    return XPathFactory.newInstance().newXPath().evaluate(expression, pom()).strip();
  }

  private static List<String> pomValues(String expression) throws Exception {
    var nodes =
        (NodeList)
            XPathFactory.newInstance()
                .newXPath()
                .evaluate(expression, pom(), XPathConstants.NODESET);

    return IntStream.range(0, nodes.getLength())
        .mapToObj(index -> nodes.item(index).getTextContent().strip())
        .toList();
  }

  private static Document pom() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

    return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
  }

  private static PathMatcher glob(String pattern) {
    return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
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
    return Pattern.compile("\\$\\{\\{((?:[^{}]|\\{[^{}]*+})*+)}}")
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

  // shardArgument is what GitHub renders for this job's shard expression.
  @Builder
  private record ApplicationJob(
      String suite, String shardArgument, String ref, List<String> changedFiles) {}
}
