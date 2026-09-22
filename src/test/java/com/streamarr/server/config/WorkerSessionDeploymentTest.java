package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Session Deployment Tests")
class WorkerSessionDeploymentTest {

  private static final Pattern UTF_8_LOCALE = Pattern.compile("(?i).+\\.utf-?8(@.+)?");

  @Test
  @DisplayName("Should require native image validation when the worker pin changes")
  void shouldRequireNativeImageValidationWhenWorkerPinChanges() throws IOException {
    var mapper = new ObjectMapper();
    var workflow =
        mapper.valueToTree(new Yaml().load(Files.readString(Path.of(".github/workflows/ci.yml"))));
    var filter =
        StreamSupport.stream(
                workflow.path("jobs").path("changes").path("steps").spliterator(), false)
            .filter(step -> "filter".equals(step.path("id").asString()))
            .findFirst()
            .orElseThrow();
    var configuration =
        mapper.valueToTree(new Yaml().load(filter.path("with").path("filters").asString()));
    var patterns =
        StreamSupport.stream(configuration.path("packaging").spliterator(), false)
            .map(JsonNode::asString)
            .toList();

    assertThat(patterns)
        .anyMatch(
            pattern ->
                FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern)
                    .matches(Path.of("worker-image.env")));
  }

  @Test
  @DisplayName("Should share the server network and media when using the default Compose worker")
  void shouldShareServerNetworkAndMediaWhenUsingDefaultComposeWorker() throws IOException {
    var deployment =
        new ObjectMapper()
            .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))));
    var services = deployment.path("services");
    var server = services.path("streamarr-server");
    var worker = services.path("transcode-worker");
    var environment = worker.path("environment");

    assertThat(worker.path("network_mode").asString()).isEqualTo("service:streamarr-server");
    assertThat(worker.path("image").asString())
        .isEqualTo(
            "${STREAMARR_WORKER_IMAGE:?Set STREAMARR_WORKER_IMAGE to the verified standalone worker image}");
    assertThat(worker.has("entrypoint")).isFalse();
    assertThat(environment.has("TRANSCODE_WORKER_PLAINTEXT")).isFalse();
    assertThat(environment.path("TRANSCODE_WORKER_CONTROL_PLANE_HOST").asString())
        .isEqualTo("127.0.0.1");
    assertThat(environment.path("TRANSCODE_WORKER_CONTROL_PLANE_PORT"))
        .isEqualTo(server.path("environment").path("STREAMING_WORKER_SESSION_PORT"));
    assertThat(server.path("environment").has("STREAMING_WORKER_SESSION_LOOPBACK_ENABLED"))
        .isFalse();
    assertThat(worker.path("volumes")).isEqualTo(server.path("volumes"));
    assertThat(worker.has("ports")).isFalse();
  }

  @Test
  @DisplayName(
      "Should delegate transport protection when Kubernetes workers connect through the Service")
  void shouldDelegateTransportProtectionWhenKubernetesWorkersConnectThroughService()
      throws IOException {
    var deployments = kubernetesDeployments();
    var server = environment(deployments.get("streamarr-server"));
    var worker = environment(deployments.get("streamarr-transcode-worker"));

    assertThat(server)
        .containsEntry("STREAMING_WORKER_SESSION_ADDRESS", "0.0.0.0")
        .containsEntry("STREAMING_WORKER_SESSION_PORT", "9090");
    assertThat(server.keySet()).noneMatch(name -> name.contains("TLS") || name.endsWith("ENABLED"));
    assertThat(worker)
        .containsEntry("TRANSCODE_WORKER_CONTROL_PLANE_HOST", "streamarr-server")
        .containsEntry("TRANSCODE_WORKER_CONTROL_PLANE_PORT", "9090");
    assertThat(worker.keySet())
        .noneMatch(name -> name.contains("TLS") || name.contains("PLAINTEXT"));
  }

  @Test
  @DisplayName("Should run every official process under a UTF-8 locale when deployed")
  void shouldRunEveryOfficialProcessUnderUtf8LocaleWhenDeployed() throws IOException {
    var composeServices =
        new ObjectMapper()
            .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))))
            .path("services");
    var kubernetes = kubernetesDeployments();
    var environments =
        Map.of(
            "Compose streamarr-server",
            composeEnvironment(composeServices.path("streamarr-server")),
            "Compose transcode-worker",
            composeEnvironment(composeServices.path("transcode-worker")),
            "Kubernetes streamarr-server",
            environment(kubernetes.get("streamarr-server")),
            "Kubernetes streamarr-transcode-worker",
            environment(kubernetes.get("streamarr-transcode-worker")));

    assertSoftly(
        softly ->
            environments.forEach(
                (process, environment) ->
                    softly
                        .assertThat(effectiveFilenameLocale(environment))
                        .as(process)
                        .matches(UTF_8_LOCALE)));
  }

  // POSIX precedence for the character-type category: LC_ALL, then LC_CTYPE, then LANG.
  private static String effectiveFilenameLocale(Map<String, String> environment) {
    return Stream.of("LC_ALL", "LC_CTYPE", "LANG")
        .map(environment::get)
        .filter(value -> value != null && !value.isEmpty())
        .findFirst()
        .orElse("");
  }

  private Map<String, String> composeEnvironment(JsonNode service) {
    return service.path("environment").properties().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().asString()));
  }

  private Map<String, JsonNode> kubernetesDeployments() throws IOException {
    var documents =
        new Yaml()
            .loadAll(Files.readString(Path.of("deploy/kubernetes/distributed-transcoding.yaml")));
    var mapper = new ObjectMapper();
    return StreamSupport.stream(documents.spliterator(), false)
        .<JsonNode>map(mapper::valueToTree)
        .filter(document -> "Deployment".equals(document.path("kind").asString()))
        .collect(
            Collectors.toMap(
                document -> document.path("metadata").path("name").asString(),
                document -> document));
  }

  private Map<String, String> environment(JsonNode deployment) {
    var variables =
        deployment.path("spec").path("template").path("spec").path("containers").get(0).path("env");
    return StreamSupport.stream(variables.spliterator(), false)
        .collect(
            Collectors.toMap(
                variable -> variable.path("name").asString(),
                variable -> variable.path("value").asString()));
  }
}
