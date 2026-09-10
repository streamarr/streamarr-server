package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("CI Pipeline Workflow Tests")
class CiPipelineWorkflowTest {

  @TempDir private Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"failure", "cancelled", "skipped"})
  @DisplayName("Should fail the required build when analysis did not succeed")
  void shouldFailTheRequiredBuildWhenAnalysisDidNotSucceed(String analysis) throws Exception {
    var context = successfulChecks();
    context.put("needs.analysis.result", analysis);

    var result =
        runBash(render(step("build", "Verify required checks").get("run").toString(), context));

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("Coverage and analysis verification failed: " + analysis);
  }

  @ParameterizedTest
  @CsvSource({"false, skipped, 0", "true, skipped, 1", "true, success, 0"})
  @DisplayName("Should gate packaging according to changed paths when analysis succeeds")
  void shouldGatePackagingAccordingToChangedPathsWhenAnalysisSucceeds(
      String required, String packaging, int exitCode) throws Exception {
    var context = successfulChecks();
    context.put("needs.changes.outputs.packaging", required);
    context.put("needs.package_image.result", packaging);

    var result =
        runBash(render(step("build", "Verify required checks").get("run").toString(), context));

    assertThat(result.exitCode()).as(result.output()).isEqualTo(exitCode);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unit", "integration"})
  @DisplayName("Should select complementary Maven profiles for parallel application jobs")
  void shouldSelectComplementaryMavenProfilesForParallelApplicationJobs(String suite)
      throws Exception {
    var matrix = map(map(job("application").get("strategy")).get("matrix"));
    assertThat(matrix).containsEntry("suite", List.of("unit", "integration"));
    assertThat(job("analysis")).containsEntry("needs", "application");
    var capture = temporaryDirectory.resolve("mvnw");
    Files.writeString(capture, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
    assertThat(capture.toFile().setExecutable(true)).isTrue();
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

  private static Map<String, String> successfulChecks() {
    return new HashMap<>(
        Map.of(
            "needs.changes.result", "success",
            "needs.changes.outputs.packaging", "false",
            "needs.ffmpeg_lock.result", "success",
            "needs.application.result", "success",
            "needs.package_image.result", "success",
            "needs.analysis.result", "success"));
  }

  @ParameterizedTest
  @CsvSource({
    "pull_request, refs/pull/353/merge, true",
    "pull_request, refs/heads/main, true",
    "push, refs/heads/main, true",
    "push, refs/heads/feature, false",
    "workflow_dispatch, refs/heads/main, true",
    "workflow_dispatch, refs/heads/feature, false"
  })
  @DisplayName("Should scope Sonar analysis to authenticated pull requests and main")
  void shouldScopeSonarAnalysisToAuthenticatedPullRequestsAndMain(
      String event, String ref, boolean allowedWithToken) throws Exception {
    for (var token : List.of("", "test-token")) {
      var context = Map.of("env.SONAR_TOKEN", token, "github.event_name", event, "github.ref", ref);
      for (var name : List.of("Cache SonarCloud packages", "SonarCloud analysis")) {
        var condition = step("analysis", name).get("if").toString();
        var result = runBash("[[ " + substituteContext(condition, context) + " ]]");

        assertThat(result.exitCode())
            .as(
                "%s: event=%s ref=%s tokenPresent=%s; %s",
                name, event, ref, !token.isEmpty(), result.output())
            .isEqualTo(allowedWithToken && !token.isEmpty() ? 0 : 1);
      }
    }
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
    var output = temporaryDirectory.resolve("command.log");
    var process =
        new ProcessBuilder("bash", "-e", "-c", command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
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
