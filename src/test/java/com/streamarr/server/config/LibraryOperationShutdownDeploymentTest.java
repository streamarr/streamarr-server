package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The platform stops the server's container after its grace period. The grace period outlasts the
 * library operations' default shutdown drain, so a drained operation leaves the server in time to
 * be interrupted and stored for the next start.
 */
@Tag("UnitTest")
@DisplayName("Library operation shutdown deployment tests")
class LibraryOperationShutdownDeploymentTest {

  private static final Duration GRACE_PERIOD = Duration.ofSeconds(45);

  @Test
  @DisplayName(
      "Should give the Compose server 45 seconds to stop, longer than the default library"
          + " operation drain")
  void shouldGiveTheComposeServerFortyFiveSecondsToStopLongerThanTheDefaultDrain()
      throws IOException {
    var server = yaml(Path.of("docker-compose.yml")).path("services").path("streamarr-server");

    assertThat(server.path("stop_grace_period").asString()).isEqualTo("45s");
    assertThat(GRACE_PERIOD).isGreaterThan(defaultDrainTimeout());
  }

  @Test
  @DisplayName(
      "Should give the Kubernetes server pod 45 seconds to stop, longer than the default library"
          + " operation drain")
  void shouldGiveTheKubernetesServerPodFortyFiveSecondsToStopLongerThanTheDefaultDrain()
      throws IOException {
    var podSpec = kubernetesServerDeployment().path("spec").path("template").path("spec");

    assertThat(podSpec.path("terminationGracePeriodSeconds").asLong())
        .isEqualTo(GRACE_PERIOD.toSeconds());
    assertThat(GRACE_PERIOD).isGreaterThan(defaultDrainTimeout());
  }

  private static Duration defaultDrainTimeout() {
    return LibraryOperationSchedulingProperties.builder().build().shutdownDrainTimeout();
  }

  private static JsonNode yaml(Path path) throws IOException {
    return new ObjectMapper().valueToTree(new Yaml().load(Files.readString(path)));
  }

  private static JsonNode kubernetesServerDeployment() throws IOException {
    var documents =
        new Yaml()
            .loadAll(Files.readString(Path.of("deploy/kubernetes/distributed-transcoding.yaml")));
    return StreamSupport.stream(documents.spliterator(), false)
        .filter(Map.class::isInstance)
        .map(document -> new ObjectMapper().<JsonNode>valueToTree(document))
        .filter(resource -> resource.path("kind").asString().equals("Deployment"))
        .filter(
            resource ->
                resource.path("metadata").path("name").asString().equals("streamarr-server"))
        .findFirst()
        .orElseThrow();
  }
}
