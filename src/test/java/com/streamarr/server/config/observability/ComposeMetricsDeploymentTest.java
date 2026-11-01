package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@Tag("UnitTest")
@DisplayName("Compose metrics deployment tests")
class ComposeMetricsDeploymentTest {

  private static final String STORAGE_PATH_FLAG = "--storage.tsdb.path=";
  private static final String METRICS_VOLUME = "prometheus-data";

  @Test
  @DisplayName("Should publish the Prometheus UI on the host's loopback only")
  void shouldPublishThePrometheusUiOnTheHostsLoopbackOnly() {
    var ports = ComposeFile.service("prometheus").path("ports");

    assertThat(ports.values())
        .singleElement()
        .satisfies(port -> assertThat(port.asString()).startsWith("127.0.0.1:"));
  }

  @Test
  @DisplayName("Should publish the Prometheus UI on host port 9090 when the port variable is unset")
  void shouldPublishThePrometheusUiOnHostPort9090WhenThePortVariableIsUnset() {
    var port = ComposeFile.service("prometheus").path("ports").get(0).asString();
    var hostPort = ComposeFile.withDefaults(port).split(":")[1];

    assertThat(hostPort).isEqualTo("9090");
  }

  @Test
  @DisplayName("Should keep the stored metrics in a named volume when Compose recreates Prometheus")
  void shouldKeepTheStoredMetricsInANamedVolumeWhenComposeRecreatesPrometheus() {
    var prometheus = ComposeFile.service("prometheus");
    var storagePath =
        prometheus.path("command").values().stream()
            .map(JsonNode::asString)
            .filter(argument -> argument.startsWith(STORAGE_PATH_FLAG))
            .map(argument -> argument.substring(STORAGE_PATH_FLAG.length()))
            .findFirst();

    assertThat(storagePath).as("Prometheus storage path").isPresent();
    assertThat(prometheus.path("volumes").values())
        .extracting(JsonNode::asString)
        .as("Prometheus volume mounts")
        .contains(METRICS_VOLUME + ":" + storagePath.orElseThrow());
    assertThat(ComposeFile.volumes().has(METRICS_VOLUME))
        .as("top-level declaration of the %s volume", METRICS_VOLUME)
        .isTrue();
  }
}
