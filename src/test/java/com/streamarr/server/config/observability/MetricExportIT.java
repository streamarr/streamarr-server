package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.streamarr.server.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the server under the {@code observability} profile against a local OTLP receiver in place of
 * the collector, and reads what the server sends it.
 */
@Tag("IntegrationTest")
@DisplayName("Metric export integration tests")
@ActiveProfiles("observability")
class MetricExportIT extends AbstractIntegrationTest {

  private static final Duration EXPORT_WAIT = Duration.ofSeconds(30);
  private static final String STARTUP_METER = "streamarr.probes.waiting";
  private static final String POOL_METER = "hikaricp.connections.active";
  private static final String PRODUCTION_SPEED_METER = "streamarr.streaming.production_speed";

  private static final List<ExportMetricsServiceRequest> METRIC_EXPORTS =
      new CopyOnWriteArrayList<>();
  private static final List<byte[]> TRACE_EXPORTS = new CopyOnWriteArrayList<>();
  private static final HttpServer COLLECTOR = startCollector();

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void sendTelemetryToTheLocalCollector(DynamicPropertyRegistry registry) {
    var collector = "http://127.0.0.1:" + COLLECTOR.getAddress().getPort();
    registry.add("otel.exporter.otlp.endpoint", () -> collector);
    registry.add("otel.metric.export.interval", () -> "100");
    registry.add("otel.bsp.schedule.delay", () -> "100");
    registry.add("management.otlp.metrics.export.url", () -> collector + "/v1/metrics");
    registry.add("management.otlp.metrics.export.step", () -> "1s");
  }

  @AfterAll
  static void stopCollector() {
    COLLECTOR.stop(0);
  }

  @Test
  @DisplayName("Should export the server's meters as streamarr-server when the profile is active")
  void shouldExportTheServersMetersAsStreamarrServerWhenTheProfileIsActive() {
    await()
        .atMost(EXPORT_WAIT)
        .untilAsserted(
            () ->
                assertThat(latestExportOfTheServersMeters())
                    .get()
                    .extracting(ExportMetricsServiceRequest::getResourceMetricsList)
                    .asInstanceOf(InstanceOfAssertFactories.list(ResourceMetrics.class))
                    .singleElement()
                    .satisfies(
                        resource -> assertThat(metricNames(resource)).contains(POOL_METER),
                        resource ->
                            assertThat(resourceAttribute(resource, "service.name"))
                                .hasValue("streamarr-server"),
                        resource ->
                            assertThat(resourceAttribute(resource, "deployment.environment"))
                                .hasValue("dev")));
  }

  @Test
  @DisplayName("Should stop exporting a meter when it is removed from the registry")
  void shouldStopExportingAMeterWhenItIsRemovedFromTheRegistry() {
    var worker = UUID.randomUUID().toString();
    var productionSpeed =
        DistributionSummary.builder(PRODUCTION_SPEED_METER)
            .tag("worker", worker)
            .register(meterRegistry);
    productionSpeed.record(2.5);

    await()
        .atMost(EXPORT_WAIT)
        .untilAsserted(
            () ->
                assertThat(latestExportOfTheServersMeters())
                    .hasValueSatisfying(
                        export ->
                            assertThat(workersOf(export, PRODUCTION_SPEED_METER))
                                .contains(worker)));

    meterRegistry.remove(productionSpeed);

    await()
        .atMost(EXPORT_WAIT)
        .untilAsserted(
            () ->
                assertThat(latestExportOfTheServersMeters())
                    .hasValueSatisfying(
                        export ->
                            assertThat(workersOf(export, PRODUCTION_SPEED_METER))
                                .doesNotContain(worker)));
  }

  @Test
  @DisplayName("Should export JVM metrics from one instrumentation when the profile is active")
  void shouldExportJvmMetricsFromOneInstrumentationWhenTheProfileIsActive() {
    await()
        .atMost(EXPORT_WAIT)
        .untilAsserted(
            () ->
                assertThat(scopesExporting("jvm.memory.used"::equals))
                    .containsAnyElementsOf(scopesExporting(STARTUP_METER::equals)));

    assertThat(scopesExporting(name -> name.startsWith("jvm.")))
        .hasSize(1)
        .containsExactlyElementsOf(scopesExporting(STARTUP_METER::equals));
  }

  @Test
  @DisplayName("Should keep exporting spans when metrics leave through Micrometer")
  void shouldKeepExportingSpansWhenMetricsLeaveThroughMicrometer() throws Exception {
    mockMvc.perform(get("/actuator/health"));

    await().atMost(EXPORT_WAIT).untilAsserted(() -> assertThat(TRACE_EXPORTS).isNotEmpty());
  }

  private static Optional<ExportMetricsServiceRequest> latestExportOfTheServersMeters() {
    return METRIC_EXPORTS.reversed().stream()
        .filter(
            export -> scopes(export).anyMatch(scope -> metricNames(scope).contains(STARTUP_METER)))
        .findFirst();
  }

  private static Set<String> workersOf(ExportMetricsServiceRequest export, String meter) {
    return scopes(export)
        .flatMap(scope -> scope.getMetricsList().stream())
        .filter(metric -> metric.getName().equals(meter))
        .flatMap(metric -> metric.getHistogram().getDataPointsList().stream())
        .flatMap(point -> point.getAttributesList().stream())
        .filter(attribute -> attribute.getKey().equals("worker"))
        .map(attribute -> attribute.getValue().getStringValue())
        .collect(Collectors.toSet());
  }

  private static Set<String> scopesExporting(Predicate<String> metricName) {
    return METRIC_EXPORTS.stream()
        .flatMap(MetricExportIT::scopes)
        .filter(scope -> metricNames(scope).stream().anyMatch(metricName))
        .map(scope -> scope.getScope().getName())
        .collect(Collectors.toSet());
  }

  private static Stream<ScopeMetrics> scopes(ExportMetricsServiceRequest export) {
    return export.getResourceMetricsList().stream()
        .flatMap(resource -> resource.getScopeMetricsList().stream());
  }

  private static Set<String> metricNames(ResourceMetrics resource) {
    return resource.getScopeMetricsList().stream()
        .flatMap(scope -> metricNames(scope).stream())
        .collect(Collectors.toSet());
  }

  private static Set<String> metricNames(ScopeMetrics scope) {
    return scope.getMetricsList().stream().map(Metric::getName).collect(Collectors.toSet());
  }

  private static Optional<String> resourceAttribute(ResourceMetrics resource, String key) {
    return resource.getResource().getAttributesList().stream()
        .filter(attribute -> attribute.getKey().equals(key))
        .map(KeyValue::getValue)
        .map(AnyValue::getStringValue)
        .findFirst();
  }

  private static HttpServer startCollector() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/metrics",
          exchange -> {
            try (exchange) {
              METRIC_EXPORTS.add(
                  ExportMetricsServiceRequest.parseFrom(exchange.getRequestBody().readAllBytes()));
              exchange.sendResponseHeaders(200, -1);
            }
          });
      server.createContext(
          "/v1/traces",
          exchange -> {
            try (exchange) {
              TRACE_EXPORTS.add(exchange.getRequestBody().readAllBytes());
              exchange.sendResponseHeaders(200, -1);
            }
          });
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              exchange.getRequestBody().readAllBytes();
              exchange.sendResponseHeaders(200, -1);
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
