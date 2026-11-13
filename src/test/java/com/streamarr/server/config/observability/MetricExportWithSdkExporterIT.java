package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.MICROMETER;
import static com.streamarr.server.config.observability.OtlpExports.scopesExporting;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs with {@code OTEL_METRICS_EXPORTER=otlp}, which turns the OpenTelemetry SDK's metric exporter
 * back on. A test flushes the SDK, which then exports at once everything it would send besides
 * Micrometer's export.
 */
@DisplayName("Metric Export With SDK Exporter Integration Tests")
@ContextConfiguration(initializers = MetricExportWithSdkExporterIT.DeploymentVariables.class)
class MetricExportWithSdkExporterIT extends AbstractObservabilityProfileIntegrationTest {

  private static final String OPENTELEMETRY_SDK = "opentelemetry";

  private static final RecordingOtlpCollector COLLECTOR = RecordingOtlpCollector.start(EXPORT_WAIT);

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private OpenTelemetry openTelemetry;

  private ObservableDoubleGauge sdkSentinel;

  @AfterAll
  static void stopCollector() {
    COLLECTOR.close();
  }

  // An instrument of the SDK's own gives each flush something to export, so a test can tell that
  // the SDK's exporter is on.
  @BeforeEach
  void registerSdkSentinel() {
    sdkSentinel =
        openTelemetry
            .getMeter("metric-export-test")
            .gaugeBuilder("metric_export_test.sentinel")
            .buildWithCallback(measurement -> measurement.record(1));
  }

  @AfterEach
  void closeSdkSentinel() {
    sdkSentinel.close();
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
    var removalPosition = COLLECTOR.metricExports().position();
    flushTheSdksMetrics();
    var afterRemoval = exportsCollectedAfter(removalPosition);

    assertThat(afterRemoval)
        .extracting(OtlpExports::exporter)
        .as("exporters of the exports collected after the removal")
        .contains(Optional.of(MICROMETER), Optional.of(OPENTELEMETRY_SDK));
    assertThat(afterRemoval)
        .flatMap(AbstractObservabilityProfileIntegrationTest::workersOf)
        .as("workers of the production speed meter in the exports collected after its removal")
        .doesNotContain(worker);
  }

  @Test
  @DisplayName("Should export JVM metrics from one instrumentation when the SDK exporter is on")
  void shouldExportJvmMetricsFromOneInstrumentationWhenTheSdkExporterIsOn() throws Exception {
    flushTheSdksMetrics();
    firstExportOfTheReadyServer();

    var exports = COLLECTOR.metricExports().all();
    assertThat(exports).extracting(OtlpExports::exporter).contains(Optional.of(OPENTELEMETRY_SDK));
    assertThat(scopesExporting(exports, name -> name.startsWith("jvm.")))
        .hasSize(1)
        .isEqualTo(scopesExporting(exports, STARTUP_METER::equals));
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
