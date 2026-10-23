package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Compose metrics deployment tests")
class ComposeMetricsDeploymentTest {

  @Test
  @DisplayName(
      "Should send the server's metrics to the OTLP receiver of the Compose Prometheus service")
  void shouldSendTheServersMetricsToTheOtlpReceiverOfTheComposePrometheusService()
      throws IOException {
    var services = compose().path("services");
    var endpoint =
        URI.create(
            services
                .path("streamarr-server")
                .path("environment")
                .path("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
                .asString());

    assertThat(endpoint.getHost()).isEqualTo("prometheus");
    assertThat(endpoint.getPort()).isEqualTo(9090);
    assertThat(endpoint.getPath()).isEqualTo("/api/v1/otlp/v1/metrics");
    assertThat(commandOf(services.path("prometheus"))).contains("--web.enable-otlp-receiver");
  }

  @Test
  @DisplayName("Should publish the Prometheus UI on the host's loopback only")
  void shouldPublishThePrometheusUiOnTheHostsLoopbackOnly() throws IOException {
    var ports = compose().path("services").path("prometheus").path("ports");

    assertThat(ports.values())
        .singleElement()
        .satisfies(port -> assertThat(port.asString()).startsWith("127.0.0.1:"));
  }

  private static List<String> commandOf(JsonNode service) {
    return service.path("command").values().stream().map(JsonNode::asString).toList();
  }

  private static JsonNode compose() throws IOException {
    return new ObjectMapper()
        .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))));
  }
}
