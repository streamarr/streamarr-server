package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.carryingMeter;
import static com.streamarr.server.config.observability.OtlpExports.fromMicrometer;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the server under the {@code observability} profile against a {@link RecordingOtlpCollector}
 * in place of the collector. The collector's address and a service name reach the server as the
 * OpenTelemetry environment variables that a deployment sets.
 */
@Tag("IntegrationTest")
@ActiveProfiles("observability")
abstract class AbstractObservabilityProfileIntegrationTest extends AbstractIntegrationTest {

  static final Duration EXPORT_WAIT = Duration.ofSeconds(30);
  static final String SERVICE_NAME = "streamarr-server-under-test";

  /** A meter the server registers at startup: an export carrying it exports the server's meters. */
  static final String STARTUP_METER = "streamarr.probes.waiting";

  /**
   * A meter Spring Boot registers once the server is ready. The meters that binders such as the
   * connection pool's add are registered before it, so an export carrying it carries them too.
   */
  static final String READY_METER = "application.ready.time";

  @Autowired private AutoConfiguredOpenTelemetrySdk openTelemetrySdk;

  @DynamicPropertySource
  static void exportOften(DynamicPropertyRegistry registry) {
    // The SDK exports metrics only when a test flushes it: a flush fails while an export runs.
    registry.add("otel.metric.export.interval", () -> "3600000");
    registry.add("otel.bsp.schedule.delay", () -> "100");
    registry.add("otel.blrp.schedule.delay", () -> "100");
    registry.add("management.otlp.metrics.export.step", () -> "1s");
  }

  static void setDeploymentVariables(
      ConfigurableApplicationContext context, Map<String, Object> variables) {
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new SystemEnvironmentPropertySource("deploymentVariables", variables));
  }

  static Map<String, Object> collectorVariables(RecordingOtlpCollector collector) {
    var variables = new HashMap<String, Object>();
    variables.put("OTEL_EXPORTER_OTLP_ENDPOINT", collector.endpoint());
    variables.put("OTEL_SERVICE_NAME", SERVICE_NAME);
    return variables;
  }

  abstract RecordingOtlpCollector collector();

  /**
   * Waits until Micrometer has sent two exports of the server's meters after {@code position}, and
   * returns the metric exports collected after it: every export received after the position but
   * Micrometer's first. Micrometer may have collected that one before the position, since it
   * collects its next export only after it has sent the previous one. The SDK exports only when a
   * test flushes it, so each SDK export received after the position was collected after it.
   */
  List<ExportMetricsServiceRequest> exportsCollectedAfter(int position)
      throws InterruptedException {
    var exports =
        collector()
            .metricExports()
            .awaitAfter(
                position,
                received -> carryingMeter(fromMicrometer(received), STARTUP_METER).size() >= 2);
    var collectedAfter = new ArrayList<>(exports);
    collectedAfter.remove(fromMicrometer(exports).getFirst());
    return List.copyOf(collectedAfter);
  }

  /**
   * Waits for, and returns, the first export of the server's meters collected after the position.
   */
  ExportMetricsServiceRequest exportOfTheServersMetersCollectedAfter(int position)
      throws InterruptedException {
    return carryingMeter(fromMicrometer(exportsCollectedAfter(position)), STARTUP_METER).getFirst();
  }

  /**
   * Waits for, and returns, the first export of the server's meters that Micrometer collected once
   * the server was ready. It arrives within one step of that, so only a context's first test waits.
   * The registry fixes the resource, the endpoint and the headers of its exports when the server
   * starts, so this export carries the same ones as every later export.
   */
  ExportMetricsServiceRequest firstExportOfTheReadyServer() throws InterruptedException {
    var exports =
        collector()
            .metricExports()
            .awaitAfter(
                0, received -> !carryingMeter(fromMicrometer(received), READY_METER).isEmpty());
    return carryingMeter(fromMicrometer(exports), READY_METER).getFirst();
  }

  /**
   * Has each metric reader of the OpenTelemetry SDK collect and export what the SDK holds, and
   * returns once the collector has received those exports.
   */
  void flushTheSdksMetrics() {
    var flush = openTelemetrySdk.getOpenTelemetrySdk().getSdkMeterProvider().forceFlush();

    assertThat(flush.join(EXPORT_WAIT.toMillis(), TimeUnit.MILLISECONDS).isSuccess())
        .as("The OpenTelemetry SDK flushed its metrics")
        .isTrue();
  }
}
