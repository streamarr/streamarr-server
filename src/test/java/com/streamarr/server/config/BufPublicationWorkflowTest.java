package com.streamarr.server.config;

import static com.streamarr.server.support.ProcessTestSupport.awaitCompletion;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Buf Publication Workflow Tests")
class BufPublicationWorkflowTest {

  @Test
  @DisplayName("Should verify published stubs and retain their immutable pin after publication")
  void shouldVerifyPublishedStubsAndRetainTheirImmutablePinAfterPublication() throws Exception {
    var workflow = workflow();
    var job = map(map(workflow.get("jobs")).get("publish"));
    var steps = ((List<?>) job.get("steps")).stream().map(BufPublicationWorkflowTest::map).toList();
    var publication =
        steps.stream()
            .filter(step -> map(step.get("with")).containsKey("push"))
            .findFirst()
            .orElseThrow();
    var verification =
        steps.stream()
            .filter(
                step -> step.getOrDefault("run", "").toString().contains("verify-buf-consumer.sh"))
            .findFirst()
            .orElseThrow();
    var artifact =
        steps.stream()
            .filter(
                step ->
                    step.getOrDefault("uses", "").toString().startsWith("actions/upload-artifact@"))
            .findFirst()
            .orElseThrow();

    assertThat(steps.indexOf(verification)).isGreaterThan(steps.indexOf(publication));
    assertThat(steps.indexOf(artifact)).isGreaterThan(steps.indexOf(verification));
    assertThat(map(artifact.get("with")))
        .containsEntry("path", "target/buf-consumer-pin.properties");
    assertThat(map(workflow.get("permissions"))).containsExactly(Map.entry("contents", "read"));
  }

  @Test
  @DisplayName("Should consume published Java stubs without a server parent or protobuf sources")
  void shouldConsumePublishedJavaStubsWithoutAServerParentOrProtobufSources() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var project =
        factory.newDocumentBuilder().parse(Path.of(".github/buf-consumer/pom.xml").toFile());
    var xpath = XPathFactory.newInstance().newXPath();

    assertThat(xpath.evaluate("/project/parent", project)).isEmpty();
    assertThat(xpath.evaluate("count(/project/dependencies/dependency)", project)).isEqualTo("1");
    assertThat(xpath.evaluate("/project/dependencies/dependency/groupId", project))
        .isEqualTo("build.buf.gen");
    assertThat(xpath.evaluate("/project/dependencies/dependency/artifactId", project))
        .isEqualTo("streamarr-org_transcode_grpc_java");
    assertThat(xpath.evaluate("/project/dependencies/dependency/version", project))
        .isEqualTo("${buf.sdk.version}");
    assertThat(xpath.evaluate("/project/repositories/repository/url", project))
        .isEqualTo("https://buf.build/gen/maven");
    try (var files = Files.walk(Path.of(".github/buf-consumer"))) {
      assertThat(files.filter(path -> path.toString().endsWith(".proto")).toList()).isEmpty();
    }
  }

  @Test
  @DisplayName("Should publish the server owned Protobuf module under the agreed registry name")
  void shouldPublishTheServerOwnedProtobufModuleUnderTheAgreedRegistryName() throws Exception {
    try (var input = Files.newInputStream(Path.of("buf.yaml"))) {
      Map<String, Object> module = new Yaml().load(input);

      assertThat(module)
          .containsEntry(
              "modules",
              List.of(
                  Map.of(
                      "path", "src/main/protobuf", "name", "buf.build/streamarr-org/transcode")));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "push, refs/heads/main, true",
    "push, refs/heads/feature, false",
    "pull_request, refs/heads/main, false",
    "pull_request, refs/pull/350/merge, false",
    "workflow_dispatch, refs/heads/main, false"
  })
  @DisplayName("Should publish only when the server receives a main push")
  void shouldPublishOnlyWhenTheServerReceivesAMainPush(String event, String ref, boolean expected)
      throws Exception {
    var workflow = workflow();
    var trigger = map(workflow.get("on"));
    assertThat(trigger).containsOnlyKeys("push");
    assertThat(map(trigger.get("push"))).containsOnlyKeys("branches");
    assertThat(map(trigger.get("push"))).containsEntry("branches", List.of("main"));
    var job = map(map(workflow.get("jobs")).get("publish"));
    var condition =
        job.get("if")
            .toString()
            .replace("github.event_name", "'" + event + "'")
            .replace("github.ref", "'" + ref + "'")
            .replace("github.repository", "'streamarr/streamarr-server'");

    var process = new ProcessBuilder("bash", "-c", "[[ " + condition + " ]]").start();

    awaitCompletion(process, Duration.ofSeconds(5), "publication guard completed");
    assertThat(process.exitValue()).isEqualTo(expected ? 0 : 1);
  }

  private Map<String, Object> workflow() throws Exception {
    try (var input = Files.newInputStream(Path.of(".github/workflows/buf-publish.yml"))) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (value == null) {
      return Map.of();
    }

    return (Map<String, Object>) value;
  }
}
