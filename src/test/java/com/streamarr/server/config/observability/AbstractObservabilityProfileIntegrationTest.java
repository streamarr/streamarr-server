package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.carryingMeter;

import com.streamarr.server.AbstractIntegrationTest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
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

  @DynamicPropertySource
  static void exportOften(DynamicPropertyRegistry registry) {
    registry.add("otel.metric.export.interval", () -> "100");
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
   * Waits for the second export of the server's meters after {@code position}. The first may have
   * been collected before the position; the registry collects the next export only after it has
   * sent the previous one.
   */
  ExportMetricsServiceRequest exportOfTheServersMetersCollectedAfter(int position)
      throws InterruptedException {
    var exports =
        collector()
            .metricExports()
            .awaitAfter(position, received -> carryingMeter(received, STARTUP_METER).size() >= 2);
    return carryingMeter(exports, STARTUP_METER).get(1);
  }
}
