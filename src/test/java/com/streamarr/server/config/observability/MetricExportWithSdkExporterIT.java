package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.metricsNamed;
import static com.streamarr.server.config.observability.OtlpExports.scopesExporting;
import static com.streamarr.server.config.observability.OtlpExports.stringValue;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs with {@code OTEL_METRICS_EXPORTER=otlp}, which turns the OpenTelemetry SDK's metric exporter
 * back on. The SDK then exports every 100 ms, so anything it sends besides Micrometer's export
 * reaches the collector many times between two registry steps.
 */
@DisplayName("Metric Export With SDK Exporter Integration Tests")
@ContextConfiguration(initializers = MetricExportWithSdkExporterIT.DeploymentVariables.class)
class MetricExportWithSdkExporterIT extends AbstractObservabilityProfileIntegrationTest {

  private static final String PRODUCTION_SPEED_METER = "streamarr.streaming.production_speed";

  private static final RecordingOtlpCollector COLLECTOR = RecordingOtlpCollector.start(EXPORT_WAIT);

  @Autowired private MeterRegistry meterRegistry;

  @AfterAll
  static void stopCollector() {
    COLLECTOR.close();
  }

  @Override
  RecordingOtlpCollector collector() {
    return COLLECTOR;
  }

  @Test
  @DisplayName("Should stop exporting a meter when it is removed from the registry")
  void shouldStopExportingAMeterWhenItIsRemovedFromTheRegistry() throws Exception {
    var worker = UUID.randomUUID().toString();
    var productionSpeed =
        DistributionSummary.builder(PRODUCTION_SPEED_METER)
            .tag("worker", worker)
            .register(meterRegistry);
    productionSpeed.record(2.5);
    var beforeRemoval =
        exportOfTheServersMetersCollectedAfter(COLLECTOR.metricExports().position());
    assertThat(workersOf(beforeRemoval)).contains(worker);

    meterRegistry.remove(productionSpeed);
    var afterRemoval = exportsCollectedAfter(COLLECTOR.metricExports().position());

    assertThat(afterRemoval)
        .flatMap(MetricExportWithSdkExporterIT::workersOf)
        .doesNotContain(worker);
  }

  @Test
  @DisplayName("Should export JVM metrics from one instrumentation when the SDK exporter is on")
  void shouldExportJvmMetricsFromOneInstrumentationWhenTheSdkExporterIsOn() throws Exception {
    exportOfTheServersMetersCollectedAfter(COLLECTOR.metricExports().position());

    var exports = COLLECTOR.metricExports().all();
    assertThat(scopesExporting(exports, name -> name.startsWith("jvm.")))
        .hasSize(1)
        .isEqualTo(scopesExporting(exports, STARTUP_METER::equals));
  }

  private static Set<String> workersOf(ExportMetricsServiceRequest export) {
    return metricsNamed(export, PRODUCTION_SPEED_METER).stream()
        .flatMap(metric -> metric.getHistogram().getDataPointsList().stream())
        .map(point -> stringValue(point.getAttributesList(), "worker"))
        .flatMap(Optional::stream)
        .collect(Collectors.toSet());
  }

  static class DeploymentVariables
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var variables = collectorVariables(COLLECTOR);
      variables.put("OTEL_METRICS_EXPORTER", "otlp");
      setDeploymentVariables(context, variables);
    }
  }
}
