package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Compose metrics deployment tests")
class ComposeMetricsDeploymentTest {

  @Test
  @DisplayName("Should publish the Prometheus UI on the host's loopback only")
  void shouldPublishThePrometheusUiOnTheHostsLoopbackOnly() {
    var ports = ComposeFile.service("prometheus").path("ports");

    assertThat(ports.values())
        .singleElement()
        .satisfies(port -> assertThat(port.asString()).startsWith("127.0.0.1:"));
  }
}
