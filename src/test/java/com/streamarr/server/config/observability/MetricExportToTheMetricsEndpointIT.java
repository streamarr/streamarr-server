package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs with {@code OTEL_EXPORTER_OTLP_METRICS_ENDPOINT} naming a collector other than {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT}'s, and with headers for each, as a deployment whose collectors take
 * different credentials sets them. Each collector's credentials must reach that collector alone.
 */
@DisplayName("Metric Export To The Metrics Endpoint Integration Tests")
@ContextConfiguration(initializers = MetricExportToTheMetricsEndpointIT.DeploymentVariables.class)
class MetricExportToTheMetricsEndpointIT extends AbstractObservabilityProfileIntegrationTest {

  private static final String SHARED_HEADER = "x-shared-key";
  private static final String METRICS_HEADER = "x-metrics-key";

  private static final RecordingOtlpCollector SHARED_COLLECTOR =
      RecordingOtlpCollector.start(EXPORT_WAIT);
  private static final RecordingOtlpCollector METRICS_COLLECTOR =
      RecordingOtlpCollector.start(EXPORT_WAIT);

  @AfterAll
  static void stopCollectors() {
    SHARED_COLLECTOR.close();
    METRICS_COLLECTOR.close();
  }

  @Override
  RecordingOtlpCollector collector() {
    return METRICS_COLLECTOR;
  }

  @Test
  @DisplayName("Should export metrics only to the metrics endpoint when one is set")
  void shouldExportMetricsOnlyToTheMetricsEndpointWhenOneIsSet() throws Exception {
    firstExportOfTheReadyServer();

    assertThat(SHARED_COLLECTOR.metricExports().all()).isEmpty();
  }

  @Test
  @DisplayName("Should send only the metrics headers to the metrics endpoint when both are set")
  void shouldSendOnlyTheMetricsHeadersToTheMetricsEndpointWhenBothAreSet() throws Exception {
    firstExportOfTheReadyServer();

    assertThat(METRICS_COLLECTOR.requestHeaderNames())
        .contains(METRICS_HEADER)
        .doesNotContain(SHARED_HEADER);
  }

  static class DeploymentVariables
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var variables = collectorVariables(SHARED_COLLECTOR);
      variables.put(
          "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", METRICS_COLLECTOR.endpoint() + "v1/metrics");
      variables.put("OTEL_EXPORTER_OTLP_HEADERS", SHARED_HEADER + "=shared-token");
      variables.put("OTEL_EXPORTER_OTLP_METRICS_HEADERS", METRICS_HEADER + "=metrics-token");
      setDeploymentVariables(context, variables);
    }
  }
}
