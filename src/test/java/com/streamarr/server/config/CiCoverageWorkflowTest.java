package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("CI Coverage Workflow Tests")
class CiCoverageWorkflowTest {

  private static final String AUTHORIZATION = "com/streamarr/server/services/authorization/";
  private static final String COMPILER_OUTPUTS =
      "target/maven-status/maven-compiler-plugin/compile/default-compile/createdFiles.lst";

  @TempDir private Path workspace;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(workspace.resolve("target"));
    Files.copy(Path.of("pom.xml"), workspace.resolve("pom.xml"));
    try (var files = Files.walk(Path.of(".github/actions"))) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        var destination = workspace.resolve(file);
        Files.createDirectories(destination.getParent());
        Files.copy(file, destination);
      }
    }
  }

  @Test
  @DisplayName("Should reject fingerprinting when compiled classes are missing")
  void shouldRejectFingerprintingWhenCompiledClassesAreMissing() throws Exception {
    var result = runStep("Fingerprint compiled classes");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @Test
  @DisplayName("Should reject fingerprinting when a compiler output is missing")
  void shouldRejectFingerprintingWhenACompilerOutputIsMissing() throws Exception {
    write("target/classes/example/Authorization.class", "bytecode");
    write(
        "target/maven-status/maven-compiler-plugin/compile/default-compile/createdFiles.lst",
        "example/Authorization.class\nexample/Authorization$Decision.class\n");

    var result = runStep("Fingerprint compiled classes");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @Test
  @DisplayName(
      "Should reject matching coverage inputs when authorization sources were not compiled")
  void shouldRejectMatchingCoverageInputsWhenAuthorizationSourcesWereNotCompiled()
      throws Exception {
    coverageInputs();
    write("src/main/java/" + AUTHORIZATION + "Decision.java", "interface Decision {}");

    var result = runStep("Verify complete and compatible coverage inputs");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @Test
  @DisplayName(
      "Should reject successful JaCoCo execution when an authorization class is absent from its report")
  void shouldRejectSuccessfulJacocoExecutionWhenAnAuthorizationClassIsAbsentFromItsReport()
      throws Exception {
    coverageInputs();
    write("mvnw", "#!/bin/sh\nexit 0\n");
    assertThat(workspace.resolve("mvnw").toFile().setExecutable(true)).isTrue();
    write("target/jacoco-output/merged.exec", "merged execution data");
    write(
        "target/site/jacoco-merged-test-coverage-report/jacoco.xml",
        """
        <report name="partial">
          <package name="com/streamarr/server/services/authorization">
            <class name="com/streamarr/server/services/authorization/AuthorizationService">
              <counter type="LINE" missed="0" covered="1"/>
              <counter type="BRANCH" missed="0" covered="1"/>
            </class>
          </package>
        </report>
        """);

    var result = runStep("Verify combined coverage");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINE", "BRANCH"})
  @DisplayName(
      "Should reject successful JaCoCo execution when authorization coverage is incomplete")
  void shouldRejectSuccessfulJacocoExecutionWhenAuthorizationCoverageIsIncomplete(String counter)
      throws Exception {
    coverageInputs();
    completeReport("<counter type=\"" + counter + "\" missed=\"1\" covered=\"1\"/>");

    var result = runStep("Verify combined coverage");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @Test
  @DisplayName(
      "Should accept matching artifacts and complete authorization coverage with configured exclusions")
  void shouldAcceptMatchingArtifactsAndCompleteAuthorizationCoverageWithConfiguredExclusions()
      throws Exception {
    coverageInputs();
    completeReport("<counter type=\"LINE\" missed=\"0\" covered=\"1\"/>");

    var inputs = runStep("Verify complete and compatible coverage inputs");
    var report = runStep("Verify combined coverage");

    assertThat(inputs.exitCode()).as(inputs.output()).isZero();
    assertThat(report.exitCode()).as(report.output()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "target/jacoco-output/jacoco-unit-tests.exec",
        "target/jacoco-output/jacoco-integration-tests.exec",
        "target/ci-unit-classes.sha256",
        "target/ci-integration-classes.sha256",
        COMPILER_OUTPUTS,
        "target/classes/" + AUTHORIZATION + "AuthorizationService$Decision.class"
      })
  @DisplayName("Should reject coverage inputs when an artifact is missing or empty")
  void shouldRejectCoverageInputsWhenAnArtifactIsMissingOrEmpty(String artifact) throws Exception {
    coverageInputs();
    write(artifact, "");
    var empty = runStep("Verify complete and compatible coverage inputs");
    Files.delete(workspace.resolve(artifact));
    var missing = runStep("Verify complete and compatible coverage inputs");

    assertThat(empty.exitCode()).as(empty.output()).isNotZero();
    assertThat(missing.exitCode()).as(missing.output()).isNotZero();
  }

  @Test
  @DisplayName(
      "Should reject matching stdin fingerprints when coverage inputs have no class records")
  void shouldRejectMatchingStdinFingerprintsWhenCoverageInputsHaveNoClassRecords()
      throws Exception {
    coverageInputs();
    var sentinel = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  -\n";
    write("target/ci-unit-classes.sha256", sentinel);
    write("target/ci-integration-classes.sha256", sentinel);

    var result = runStep("Verify complete and compatible coverage inputs");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "target/jacoco-output/merged.exec",
        "target/site/jacoco-merged-test-coverage-report/jacoco.xml"
      })
  @DisplayName("Should reject successful JaCoCo execution when its output is missing or empty")
  void shouldRejectSuccessfulJacocoExecutionWhenItsOutputIsMissingOrEmpty(String artifact)
      throws Exception {
    coverageInputs();
    completeReport("<counter type=\"LINE\" missed=\"0\" covered=\"1\"/>");
    write(artifact, "");
    var empty = runStep("Verify combined coverage");
    Files.delete(workspace.resolve(artifact));
    var missing = runStep("Verify combined coverage");

    assertThat(empty.exitCode()).as(empty.output()).isNotZero();
    assertThat(missing.exitCode()).as(missing.output()).isNotZero();
  }

  @Test
  @DisplayName(
      "Should reject successful JaCoCo execution when no authorization lines were measured")
  void shouldRejectSuccessfulJacocoExecutionWhenNoAuthorizationLinesWereMeasured()
      throws Exception {
    coverageInputs();
    completeReport("");

    var result = runStep("Verify combined coverage");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  private void completeReport(String counters) throws Exception {
    write("mvnw", "#!/bin/sh\nexit 0\n");
    assertThat(workspace.resolve("mvnw").toFile().setExecutable(true)).isTrue();
    write("target/jacoco-output/merged.exec", "merged execution data");
    write(
        "target/site/jacoco-merged-test-coverage-report/jacoco.xml",
        """
        <report name="complete">
          <package name="com/streamarr/server/services/authorization">
            <class name="com/streamarr/server/services/authorization/AuthorizationService">
              %s
            </class>
            <class name="com/streamarr/server/services/authorization/AuthorizationService$Decision"/>
          </package>
        </report>
        """
            .formatted(counters));
  }

  private void coverageInputs() throws Exception {
    write(
        "src/main/java/" + AUTHORIZATION + "AuthorizationService.java",
        "class AuthorizationService {}");
    write("target/classes/" + AUTHORIZATION + "AuthorizationService.class", "bytecode");
    write(
        "target/classes/" + AUTHORIZATION + "AuthorizationService$Decision.class",
        "nested bytecode");
    var launcher = AUTHORIZATION + "cedar/CedarEngineSelfCheckLauncher";
    write("src/main/java/" + launcher + ".java", "class CedarEngineSelfCheckLauncher {}");
    write("target/classes/" + launcher + ".class", "excluded launcher bytecode");
    write(
        COMPILER_OUTPUTS,
        AUTHORIZATION
            + "AuthorizationService.class\n"
            + AUTHORIZATION
            + "AuthorizationService$Decision.class\n"
            + launcher
            + ".class\n");
    write("target/jacoco-output/jacoco-unit-tests.exec", "unit execution data");
    write("target/jacoco-output/jacoco-integration-tests.exec", "integration execution data");
    var fingerprint = runStep("Fingerprint compiled classes");
    assertThat(fingerprint.exitCode()).as(fingerprint.output()).isZero();
    Files.copy(
        workspace.resolve("target/ci-unit-classes.sha256"),
        workspace.resolve("target/ci-integration-classes.sha256"));
  }

  private void write(String path, String content) throws Exception {
    var destination = workspace.resolve(path);
    Files.createDirectories(destination.getParent());
    Files.writeString(destination, content);
  }

  private CommandResult runStep(String name) throws Exception {
    Map<String, Object> workflow;
    try (var input = Files.newInputStream(Path.of(".github/workflows/ci.yml"))) {
      workflow = new Yaml().load(input);
    }

    var jobs = map(workflow.get("jobs"));
    var step =
        jobs.values().stream()
            .flatMap(job -> steps(map(job).get("steps")).stream())
            .filter(candidate -> name.equals(candidate.get("name")))
            .findFirst()
            .orElseThrow();
    var command = new ArrayList<>(List.of("bash", "-e"));
    if ("bash".equals(step.get("shell"))) {
      command.addAll(List.of("-o", "pipefail"));
    }

    command.addAll(
        List.of("-c", step.get("run").toString().replace("${{ matrix.suite }}", "unit")));
    var output = workspace.resolve("command.log");
    var process =
        new ProcessBuilder(command)
            .directory(workspace.toFile())
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
