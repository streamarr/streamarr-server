package com.streamarr.server.fixtures.mesh;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Mesh Deployment Tests")
class WorkerMeshDeploymentTest {

  @Test
  @DisplayName("Should require mesh identity when workers connect through the server Service")
  void shouldRequireMeshIdentityWhenWorkersConnectThroughTheServerService() throws IOException {
    var authentication = resource("PeerAuthentication", "streamarr-worker-sessions");
    var authorization = resource("AuthorizationPolicy", "streamarr-worker-sessions");

    assertThat(authentication.at("/spec/selector/matchLabels/app.kubernetes.io~1name").asString())
        .isEqualTo("streamarr-server");
    assertThat(authentication.at("/spec/portLevelMtls/9090/mode").asString()).isEqualTo("STRICT");
    assertThat(authorization.at("/spec/selector/matchLabels/app.kubernetes.io~1name").asString())
        .isEqualTo("streamarr-server");
    assertThat(authorization.at("/spec/action").asString()).isEqualTo("DENY");
    assertThat(authorization.at("/spec/rules/0/from/0/source/notPrincipals/0").asString())
        .isEqualTo("cluster.local/ns/streamarr/sa/streamarr-transcode-worker");
    assertThat(authorization.at("/spec/rules/0/to/0/operation/ports/0").asString())
        .isEqualTo("9090");
  }

  @Test
  @DisplayName("Should preserve ordinary HTTP access when the worker port requires mesh identity")
  void shouldPreserveOrdinaryHttpAccessWhenTheWorkerPortRequiresMeshIdentity() throws IOException {
    var authentication = resource("PeerAuthentication", "streamarr-worker-sessions");
    var authorization = resource("AuthorizationPolicy", "streamarr-worker-sessions");

    assertThat(authentication.path("spec").has("mtls")).isFalse();
    assertThat(authorization.at("/spec/action").asString()).isEqualTo("DENY");
    assertThat(authorization.at("/spec/rules").size()).isEqualTo(1);
    assertThat(authorization.at("/spec/rules/0/to/0/operation/ports").size()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should inject the mesh and expose HTTP health probes when deploying workers")
  void shouldInjectTheMeshAndExposeHttpHealthProbesWhenDeployingWorkers() throws IOException {
    var namespace = resource("Namespace", "streamarr");
    var server = resource("Deployment", "streamarr-server");
    var worker = resource("Deployment", "streamarr-transcode-worker");
    var container = worker.at("/spec/template/spec/containers/0");

    assertThat(namespace.at("/metadata/labels/istio-injection").asString()).isEqualTo("enabled");
    assertThat(server.at("/spec/template/spec/serviceAccountName").asString())
        .isEqualTo("streamarr-server");
    assertThat(worker.at("/spec/template/spec/serviceAccountName").asString())
        .isEqualTo("streamarr-transcode-worker");
    assertThat(container.at("/livenessProbe/httpGet/path").asString())
        .isEqualTo("/actuator/health/liveness");
    assertThat(container.at("/readinessProbe/httpGet/path").asString())
        .isEqualTo("/actuator/health/readiness");
    assertThat(container.at("/livenessProbe/httpGet/port").asString()).isEqualTo("http-health");
    assertThat(container.at("/ports/0/containerPort").asInt()).isEqualTo(9091);
  }

  private JsonNode resource(String kind, String name) throws IOException {
    var documents =
        new Yaml()
            .loadAll(Files.readString(Path.of("deploy/kubernetes/distributed-transcoding.yaml")));
    var mapper = new ObjectMapper();
    List<JsonNode> matches =
        StreamSupport.stream(documents.spliterator(), false)
            .<JsonNode>map(mapper::valueToTree)
            .filter(document -> kind.equals(document.path("kind").asString()))
            .filter(document -> name.equals(document.at("/metadata/name").asString()))
            .toList();
    assertThat(matches).as("%s/%s must occur exactly once", kind, name).hasSize(1);
    return matches.getFirst();
  }
}
