package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.MICROMETER;
import static com.streamarr.server.config.observability.OtlpExports.attribute;
import static com.streamarr.server.config.observability.OtlpExports.hex;
import static com.streamarr.server.config.observability.OtlpExports.histogramExemplars;
import static com.streamarr.server.config.observability.OtlpExports.metricNames;
import static com.streamarr.server.config.observability.OtlpExports.metricsNamed;
import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.support.AuthTestSupport;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@DisplayName("Metric Export Integration Tests")
@ContextConfiguration(initializers = MetricExportIT.DeploymentVariables.class)
@Import(MetricExportIT.AsyncResponseController.class)
class MetricExportIT extends AbstractObservabilityProfileIntegrationTest {

  private static final String POOL_METER = "hikaricp.connections.active";
  private static final String HTTP_SERVER_METER = "http.server.requests";
  private static final String DEPLOYMENT_ENVIRONMENT = "metric-export-test";
  private static final String TRACEPARENT = "traceparent";

  private static final RecordingOtlpCollector COLLECTOR = RecordingOtlpCollector.start(EXPORT_WAIT);

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
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
  @DisplayName(
      "Should export the server's meters under the deployment variables when the profile is active")
  void shouldExportTheServersMetersUnderTheDeploymentVariablesWhenTheProfileIsActive()
      throws Exception {
    var export = firstExportOfTheReadyServer();

    assertThat(metricNames(export)).contains(POOL_METER);
    assertThat(export.getResourceMetricsList())
        .singleElement()
        .satisfies(
            resource ->
                assertThat(attribute(resource.getResource(), "service.name"))
                    .hasValue(SERVICE_NAME),
            resource ->
                assertThat(attribute(resource.getResource(), "deployment.environment"))
                    .hasValue(DEPLOYMENT_ENVIRONMENT));
  }

  @Test
  @DisplayName(
      "Should export no metrics from the OpenTelemetry SDK when OTEL_METRICS_EXPORTER is unset")
  void shouldExportNoMetricsFromTheOpenTelemetrySdkWhenOtelMetricsExporterIsUnset()
      throws Exception {
    // The request gives the SDK's HTTP server histogram a measurement to export.
    mockMvc.perform(get("/actuator/health"));

    // A reader that OTEL_METRICS_EXPORTER turned on would export it before the flush returns.
    flushTheSdksMetrics();
    firstExportOfTheReadyServer();

    assertThat(COLLECTOR.metricExports().all())
        .flatMap(ExportMetricsServiceRequest::getResourceMetricsList)
        .extracting(resource -> attribute(resource.getResource(), "telemetry.sdk.name"))
        .containsOnly(Optional.of(MICROMETER));
  }

  // Compose sends its metrics as this context does: through Micrometer's registry alone.
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
        .flatMap(AbstractObservabilityProfileIntegrationTest::workersOf)
        .as("workers of the production speed meter in the exports collected after its removal")
        .doesNotContain(worker);
  }

  @Test
  @DisplayName("Should export HTTP server latency buckets when a request is served")
  void shouldExportHttpServerLatencyBucketsWhenARequestIsServed() throws Exception {
    mockMvc.perform(get("/actuator/health"));

    var export = exportOfTheServersMetersCollectedAfter(COLLECTOR.metricExports().position());

    assertThat(metricsNamed(export, HTTP_SERVER_METER))
        .flatMap(metric -> metric.getHistogram().getDataPointsList())
        .isNotEmpty()
        .allSatisfy(point -> assertThat(point.getExplicitBoundsList()).isNotEmpty());
  }

  @Test
  @DisplayName("Should link HTTP server latency to the request's span when a request is served")
  void shouldLinkHttpServerLatencyToTheRequestsSpanWhenARequestIsServed() throws Exception {
    var traceId = UUID.randomUUID().toString().replace("-", "");
    var callerSpanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    var metricPosition = COLLECTOR.metricExports().position();
    var tracePosition = COLLECTOR.traceExports().position();

    mockMvc.perform(
        get("/actuator/health").header(TRACEPARENT, traceparent(traceId, callerSpanId)));

    var serverSpan = awaitServerSpan(tracePosition, traceId, callerSpanId);

    assertThat(histogramExemplars(exportsCollectedSince(metricPosition), HTTP_SERVER_METER))
        .extracting(exemplar -> hex(exemplar.getTraceId()), exemplar -> hex(exemplar.getSpanId()))
        .contains(tuple(traceId, hex(serverSpan.getSpanId())));
  }

  @Test
  @DisplayName(
      "Should link HTTP server latency to the request's span when a request completes"
          + " asynchronously")
  void shouldLinkHttpServerLatencyToTheRequestsSpanWhenARequestCompletesAsynchronously()
      throws Exception {
    var identity = authTestSupport.createIdentity();
    try {
      var traceId = UUID.randomUUID().toString().replace("-", "");
      var callerSpanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
      var metricPosition = COLLECTOR.metricExports().position();
      var tracePosition = COLLECTOR.traceExports().position();

      var started =
          mockMvc
              .perform(
                  get(AsyncResponseController.PATH)
                      .with(bearer(authTestSupport.accountBearer(identity)))
                      .header(TRACEPARENT, traceparent(traceId, callerSpanId)))
              .andExpect(request().asyncStarted())
              .andReturn();
      // The observation stops when the async request completes, after this dispatch.
      mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

      var serverSpan = awaitServerSpan(tracePosition, traceId, callerSpanId);

      assertThat(histogramExemplars(exportsCollectedSince(metricPosition), HTTP_SERVER_METER))
          .extracting(exemplar -> hex(exemplar.getTraceId()), exemplar -> hex(exemplar.getSpanId()))
          .contains(tuple(traceId, hex(serverSpan.getSpanId())));
    } finally {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should export spans under the service name of the metrics when a request is served")
  void shouldExportSpansUnderTheServiceNameOfTheMetricsWhenARequestIsServed() throws Exception {
    var position = COLLECTOR.traceExports().position();
    mockMvc.perform(get("/actuator/health"));

    COLLECTOR.traceExports().awaitAfter(position, received -> !received.isEmpty());

    assertThat(COLLECTOR.traceExports().all())
        .flatMap(ExportTraceServiceRequest::getResourceSpansList)
        .extracting(spans -> attribute(spans.getResource(), "service.name"))
        .containsOnly(Optional.of(SERVICE_NAME));
  }

  @Test
  @DisplayName("Should export metrics under the resource of the spans when a request is served")
  void shouldExportMetricsUnderTheResourceOfTheSpansWhenARequestIsServed() throws Exception {
    var spanResource = spanResourceOfARequest();
    var metricResource = firstExportOfTheReadyServer().getResourceMetrics(0).getResource();

    // telemetry.* names the SDK that sent the export, which differs by design. Micrometer sends
    // every attribute as a string, so the span attributes are compared in their string form.
    assertThat(attributesAsText(metricResource))
        .containsKeys("service.instance.id", "host.name", "process.pid")
        .isEqualTo(attributesAsText(spanResource));
  }

  @Test
  @DisplayName(
      "Should export spans and metrics without the process command line when a request is served")
  void shouldExportSpansAndMetricsWithoutTheProcessCommandLineWhenARequestIsServed()
      throws Exception {
    var spanResource = spanResourceOfARequest();
    var metricResource = firstExportOfTheReadyServer().getResourceMetrics(0).getResource();

    // A command line can carry credentials, such as a -D system property naming a token.
    assertThat(List.of(spanResource, metricResource))
        .allSatisfy(
            resource ->
                assertThat(resource.getAttributesList())
                    .extracting(KeyValue::getKey)
                    .contains("process.executable.path")
                    .doesNotContain("process.command_args", "process.command_line"));
  }

  @Test
  @DisplayName("Should export logs under the service name of the metrics when the server logs")
  void shouldExportLogsUnderTheServiceNameOfTheMetricsWhenTheServerLogs() throws Exception {
    var message = "Metric export log " + UUID.randomUUID();
    var position = COLLECTOR.logExports().position();
    log.info(message);

    var exports =
        COLLECTOR
            .logExports()
            .awaitAfter(position, received -> !resourcesLogging(received, message).isEmpty());

    assertThat(resourcesLogging(exports, message))
        .extracting(resource -> attribute(resource, "service.name"))
        .containsOnly(Optional.of(SERVICE_NAME));
  }

  private Resource spanResourceOfARequest() throws Exception {
    var position = COLLECTOR.traceExports().position();
    mockMvc.perform(get("/actuator/health"));

    return COLLECTOR
        .traceExports()
        .awaitAfter(position, received -> !received.isEmpty())
        .getFirst()
        .getResourceSpans(0)
        .getResource();
  }

  private static Map<String, String> attributesAsText(Resource resource) {
    return resource.getAttributesList().stream()
        .filter(attribute -> !attribute.getKey().startsWith("telemetry."))
        .collect(Collectors.toMap(KeyValue::getKey, attribute -> text(attribute.getValue())));
  }

  // OpenTelemetry's rule for a non-OTLP string form: an array becomes a JSON array.
  private static String text(AnyValue value) {
    return switch (value.getValueCase()) {
      case ARRAY_VALUE ->
          JsonMapper.shared()
              .writeValueAsString(
                  value.getArrayValue().getValuesList().stream()
                      .map(MetricExportIT::scalar)
                      .toList());
      default -> String.valueOf(scalar(value));
    };
  }

  private static Object scalar(AnyValue value) {
    return switch (value.getValueCase()) {
      case STRING_VALUE -> value.getStringValue();
      case INT_VALUE -> value.getIntValue();
      case DOUBLE_VALUE -> value.getDoubleValue();
      case BOOL_VALUE -> value.getBoolValue();
      default -> throw new AssertionError("Unexpected resource attribute value: " + value);
    };
  }

  private static String traceparent(String traceId, String callerSpanId) {
    return "00-%s-%s-01".formatted(traceId, callerSpanId);
  }

  private Span awaitServerSpan(int tracePosition, String traceId, String callerSpanId)
      throws InterruptedException {
    var exports =
        COLLECTOR
            .traceExports()
            .awaitAfter(
                tracePosition, received -> serverSpan(received, traceId, callerSpanId).isPresent());
    return serverSpan(exports, traceId, callerSpanId).orElseThrow();
  }

  private static Optional<Span> serverSpan(
      List<ExportTraceServiceRequest> exports, String traceId, String callerSpanId) {
    return exports.stream()
        .flatMap(export -> export.getResourceSpansList().stream())
        .flatMap(resource -> resource.getScopeSpansList().stream())
        .flatMap(scope -> scope.getSpansList().stream())
        .filter(span -> span.getKind() == Span.SpanKind.SPAN_KIND_SERVER)
        .filter(span -> hex(span.getTraceId()).equals(traceId))
        .filter(span -> hex(span.getParentSpanId()).equals(callerSpanId))
        .findFirst();
  }

  /**
   * Returns the metric exports received since {@code position}, once one collected after a
   * measurement made before this call has arrived. The registry sends an exemplar once, in the
   * first export it collects after the measurement, so that export is among those returned.
   */
  private List<ExportMetricsServiceRequest> exportsCollectedSince(int position)
      throws InterruptedException {
    exportsCollectedAfter(COLLECTOR.metricExports().position());
    var exports = COLLECTOR.metricExports().all();
    return exports.subList(position, exports.size());
  }

  private static List<Resource> resourcesLogging(
      List<ExportLogsServiceRequest> exports, String message) {
    return exports.stream()
        .flatMap(export -> export.getResourceLogsList().stream())
        .filter(
            resource ->
                resource.getScopeLogsList().stream()
                    .flatMap(scope -> scope.getLogRecordsList().stream())
                    .anyMatch(logRecord -> logRecord.getBody().getStringValue().equals(message)))
        .map(ResourceLogs::getResource)
        .toList();
  }

  /** Answers on a request thread other than the one that received the request. */
  @RestController
  static class AsyncResponseController {

    static final String PATH = "/metric-export-test/async";

    @GetMapping(PATH)
    Callable<String> respondAsynchronously() {
      return () -> "answered";
    }
  }

  static class DeploymentVariables
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var variables = collectorVariables(COLLECTOR);
      variables.put("DEPLOYMENT_ENV", DEPLOYMENT_ENVIRONMENT);
      setDeploymentVariables(context, variables);
    }
  }
}
