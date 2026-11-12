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
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The platform kills the server's container once its grace period ends. The server stops its
 * background work and its web server within one shutdown phase, and running work gets the
 * db-scheduler shutdown wait of that phase before it is interrupted and released for the next
 * start.
 */
@Tag("UnitTest")
@DisplayName("Server shutdown deployment tests")
class ServerShutdownDeploymentTest {

  private static final Duration GRACE_PERIOD = Duration.ofSeconds(15);

  @Test
  @DisplayName("Should give the server 15 seconds to stop when Compose runs it")
  void shouldGiveTheServerFifteenSecondsToStopWhenComposeRunsIt() throws IOException {
    var server = yaml(Path.of("docker-compose.yml")).path("services").path("streamarr-server");

    assertThat(server.path("stop_grace_period").asString()).isEqualTo("15s");
  }

  @Test
  @DisplayName("Should give the server 15 seconds to stop when the Kubernetes manifest runs it")
  void shouldGiveTheServerFifteenSecondsToStopWhenTheKubernetesManifestRunsIt() throws IOException {
    var podSpec = kubernetesServerDeployment().path("spec").path("template").path("spec");

    assertThat(podSpec.path("terminationGracePeriodSeconds").asLong())
        .isEqualTo(GRACE_PERIOD.toSeconds());
  }

  @Test
  @DisplayName(
      "Should interrupt running work within the shutdown phase, and end the phase within the grace"
          + " period, when the packaged configuration sets both waits")
  void shouldInterruptRunningWorkWithinTheShutdownPhaseWhenThePackagedConfigurationSetsBothWaits()
      throws IOException {
    var configuration = yaml(Path.of("src/main/resources/application.yml"));
    var shutdownPhase =
        duration(configuration.path("spring").path("lifecycle").path("timeout-per-shutdown-phase"));
    var schedulerWait = duration(configuration.path("db-scheduler").path("shutdown-max-wait"));

    assertThat(schedulerWait).isLessThan(shutdownPhase);
    assertThat(shutdownPhase).isLessThan(GRACE_PERIOD);
  }

  private static Duration duration(JsonNode value) {
    return DurationStyle.detectAndParse(value.asString());
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
