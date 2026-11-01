package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@Tag("UnitTest")
@DisplayName("Compose metrics deployment tests")
class ComposeMetricsDeploymentTest {

  @Test
  @DisplayName(
      "Should send the server's metrics to the OTLP receiver of the Compose Prometheus service")
  void shouldSendTheServersMetricsToTheOtlpReceiverOfTheComposePrometheusService() {
    var endpoint =
        URI.create(
            ComposeFile.service("streamarr-server")
                .path("environment")
                .path("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
                .asString());

    assertThat(endpoint.getHost()).isEqualTo("prometheus");
    assertThat(endpoint.getPort()).isEqualTo(9090);
    assertThat(endpoint.getPath()).isEqualTo("/api/v1/otlp/v1/metrics");
    assertThat(commandOf(ComposeFile.service("prometheus"))).contains("--web.enable-otlp-receiver");
  }

  @Test
  @DisplayName("Should publish the Prometheus UI on the host's loopback only")
  void shouldPublishThePrometheusUiOnTheHostsLoopbackOnly() {
    var ports = ComposeFile.service("prometheus").path("ports");

    assertThat(ports.values())
        .singleElement()
        .satisfies(port -> assertThat(port.asString()).startsWith("127.0.0.1:"));
  }

  private static List<String> commandOf(JsonNode service) {
    return service.path("command").values().stream().map(JsonNode::asString).toList();
  }
}
