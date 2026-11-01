package com.streamarr.server.config.observability;

import static com.streamarr.server.config.observability.OtlpExports.attribute;
import static com.streamarr.server.config.observability.OtlpExports.metricNames;
import static com.streamarr.server.config.observability.OtlpExports.metricsNamed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("Metric Export Integration Tests")
@ContextConfiguration(initializers = MetricExportIT.DeploymentVariables.class)
class MetricExportIT extends AbstractObservabilityProfileIntegrationTest {

  private static final String POOL_METER = "hikaricp.connections.active";
  private static final String HTTP_SERVER_METER = "http.server.requests";

  private static final RecordingOtlpCollector COLLECTOR = RecordingOtlpCollector.start(EXPORT_WAIT);

  @Autowired private MockMvc mockMvc;

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
      "Should export the server's meters under the service name when the profile is active")
  void shouldExportTheServersMetersUnderTheServiceNameWhenTheProfileIsActive() throws Exception {
    var export = exportOfTheServersMetersCollectedAfter(COLLECTOR.metricExports().position());

    assertThat(metricNames(export)).contains(POOL_METER);
    assertThat(export.getResourceMetricsList())
        .singleElement()
        .satisfies(
            resource ->
                assertThat(attribute(resource.getResource(), "service.name"))
                    .hasValue(SERVICE_NAME),
            resource ->
                assertThat(attribute(resource.getResource(), "deployment.environment"))
                    .hasValue("dev"));
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

  static class DeploymentVariables
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      setDeploymentVariables(context, collectorVariables(COLLECTOR));
    }
  }
}
