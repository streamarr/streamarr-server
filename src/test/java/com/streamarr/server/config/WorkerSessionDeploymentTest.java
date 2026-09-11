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
    assertThat(worker.path("entrypoint").asString()).isEqualTo("worker");
    assertThat(environment.path("TRANSCODE_WORKER_PLAINTEXT").asString()).isEqualTo("true");
    assertThat(environment.path("TRANSCODE_WORKER_CONTROL_PLANE_HOST").asString())
        .isEqualTo("127.0.0.1");
    assertThat(environment.path("TRANSCODE_WORKER_CONTROL_PLANE_PORT"))
        .isEqualTo(server.path("environment").path("STREAMING_WORKER_SESSION_LOOPBACK_PORT"));
    assertThat(
            server.path("environment").path("STREAMING_WORKER_SESSION_LOOPBACK_ENABLED").asString())
        .isEqualTo("true");
    assertThat(worker.path("volumes")).isEqualTo(server.path("volumes"));
    assertThat(worker.has("ports")).isFalse();
  }

  @Test
  @DisplayName("Should retain mutual TLS through the Service when using Kubernetes workers")
  void shouldRetainMutualTlsThroughServiceWhenUsingKubernetesWorkers() throws IOException {
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
        .containsEntry("STREAMING_WORKER_SESSION_MUTUAL_TLS_ENABLED", "true")
        .containsEntry("STREAMING_WORKER_SESSION_LOOPBACK_ENABLED", "false")
        .containsEntry("STREAMING_WORKER_SESSION_MUTUAL_TLS_PORT", "9090")
        .containsKey("STREAMING_WORKER_SESSION_MUTUAL_TLS_TRUST_BUNDLE");
    assertThat(worker)
        .containsEntry("TRANSCODE_WORKER_PLAINTEXT", "false")
        .containsEntry("TRANSCODE_WORKER_CONTROL_PLANE_HOST", "streamarr-server")
        .containsEntry("TRANSCODE_WORKER_CONTROL_PLANE_PORT", "9090");
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
