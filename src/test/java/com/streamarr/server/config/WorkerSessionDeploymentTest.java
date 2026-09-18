package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
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
    var documents =
        new Yaml()
            .loadAll(Files.readString(Path.of("deploy/kubernetes/distributed-transcoding.yaml")));
    var mapper = new ObjectMapper();
    var deployments =
        StreamSupport.stream(documents.spliterator(), false)
            .<JsonNode>map(mapper::valueToTree)
            .filter(document -> "Deployment".equals(document.path("kind").asString()))
            .collect(
                Collectors.toMap(
                    document -> document.path("metadata").path("name").asString(),
                    document -> document));
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
