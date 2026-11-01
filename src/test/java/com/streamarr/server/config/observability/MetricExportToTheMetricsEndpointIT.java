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
 * OTEL_EXPORTER_OTLP_ENDPOINT}'s. Micrometer attaches {@code OTEL_EXPORTER_OTLP_METRICS_HEADERS} to
 * every metric export, so metrics that went to the shared endpoint would carry the metrics
 * collector's credentials to another collector.
 */
@DisplayName("Metric Export To The Metrics Endpoint Integration Tests")
@ContextConfiguration(initializers = MetricExportToTheMetricsEndpointIT.DeploymentVariables.class)
class MetricExportToTheMetricsEndpointIT extends AbstractObservabilityProfileIntegrationTest {

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
    exportOfTheServersMetersCollectedAfter(METRICS_COLLECTOR.metricExports().position());

    assertThat(SHARED_COLLECTOR.metricExports().all()).isEmpty();
  }

  static class DeploymentVariables
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var variables = collectorVariables(SHARED_COLLECTOR);
      variables.put(
          "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", METRICS_COLLECTOR.endpoint() + "v1/metrics");
      setDeploymentVariables(context, variables);
    }
  }
}
