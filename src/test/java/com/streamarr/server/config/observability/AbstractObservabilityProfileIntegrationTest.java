package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.carryingMeter;
import static com.streamarr.server.config.observability.OtlpExports.fromMicrometer;

import com.streamarr.server.AbstractIntegrationTest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
   * Waits for the server's meters to be exported twice by Micrometer after {@code position}, and
   * returns the metric exports collected after it. Each exporter's first export after the position
   * may have been collected before it and is left out: an exporter collects its next export only
   * after it has sent the previous one.
   */
  List<ExportMetricsServiceRequest> exportsCollectedAfter(int position)
      throws InterruptedException {
    var exports =
        collector()
            .metricExports()
            .awaitAfter(
                position,
                received -> carryingMeter(fromMicrometer(received), STARTUP_METER).size() >= 2);
    return exports.stream()
        .collect(
            Collectors.groupingBy(OtlpExports::exporter, LinkedHashMap::new, Collectors.toList()))
        .values()
        .stream()
        .flatMap(exporterExports -> exporterExports.stream().skip(1))
        .toList();
  }

  /**
   * Waits for, and returns, the first export of the server's meters collected after the position.
   */
  ExportMetricsServiceRequest exportOfTheServersMetersCollectedAfter(int position)
      throws InterruptedException {
    return carryingMeter(fromMicrometer(exportsCollectedAfter(position)), STARTUP_METER).getFirst();
  }
}
