package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the Prometheus service of {@code docker-compose.yml} and the server with the server's
 * Compose settings, then reads what Prometheus stores. The settings reach the server as environment
 * variables, so the test applies them the same way, and only replaces the Compose address of
 * Prometheus with the container's.
 */
@Tag("IntegrationTest")
@DisplayName("Compose metrics export integration tests")
@ContextConfiguration(initializers = ComposeMetricsExportIT.ComposeServerEnvironment.class)
class ComposeMetricsExportIT extends AbstractIntegrationTest {

  private static final String COMPOSE_PROMETHEUS_ADDRESS = "http://prometheus:9090";
  private static final Pattern METRICS_VARIABLE =
      Pattern.compile("OTEL_.*|MANAGEMENT_.*|SPRING_APPLICATION_NAME");
  private static final Pattern COMPOSE_DEFAULT = Pattern.compile("\\$\\{[^:}]+:-([^}]*)}");
  private static final Duration STORAGE_WAIT = Duration.ofSeconds(60);
  // The Compose documentation promises an export every 10 seconds.
  private static final double EXPORT_STEP_SECONDS = 10;
  private static final double EXPORT_STEP_TOLERANCE_SECONDS = 4;
  // Shorter than one export step, so a larger gap means a later export left the meter out.
  private static final double LATER_EXPORT_GAP_SECONDS = EXPORT_STEP_SECONDS / 2;
  private static final String PROBES_WAITING = "__name__=\"streamarr_probes_waiting\"";
  private static final String SERVICE = "streamarr-server";

  private static final GenericContainer<?> PROMETHEUS = startPrometheus();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private MeterRegistry meterRegistry;

  @AfterAll
  static void stopPrometheus() {
    HTTP.close();
    PROMETHEUS.stop();
  }

  @Test
  @DisplayName(
      "Should store the server's meters in Prometheus when it runs with the Compose settings")
  void shouldStoreTheServersMetersInPrometheusWhenItRunsWithTheComposeSettings() {
    await()
        .atMost(STORAGE_WAIT)
        .untilAsserted(
            () ->
                assertThat(storedMetricNames())
                    .contains("streamarr_probes_waiting", "hikaricp_connections_active"));
  }

  @Test
  @DisplayName(
      "Should store a sample every ten seconds when the server runs with the Compose settings")
  void shouldStoreASampleEveryTenSecondsWhenTheServerRunsWithTheComposeSettings() {
    await()
        .atMost(STORAGE_WAIT)
        .untilAsserted(
            () ->
                assertThat(sampleSeconds(PROBES_WAITING))
                    .as("stored samples")
                    .hasSizeGreaterThan(1));

    var samples = sampleSeconds(PROBES_WAITING);
    var lastGap = samples.getLast() - samples.get(samples.size() - 2);

    assertThat(lastGap)
        .as("seconds between the last two stored samples of streamarr_probes_waiting")
        .isCloseTo(EXPORT_STEP_SECONDS, within(EXPORT_STEP_TOLERANCE_SECONDS));
  }

  @Test
  @DisplayName("Should stop storing a meter in Prometheus when it is removed from the registry")
  void shouldStopStoringAMeterInPrometheusWhenItIsRemovedFromTheRegistry() {
    var worker = UUID.randomUUID().toString();
    var productionSpeed =
        DistributionSummary.builder("streamarr.streaming.production_speed")
            .tag("worker", worker)
            .register(meterRegistry);
    productionSpeed.record(2.5);
    var workerMatchers =
        "__name__=~\"streamarr_streaming_production_speed.*\", worker=\"" + worker + "\"";

    await()
        .atMost(STORAGE_WAIT)
        .untilAsserted(() -> assertThat(latestSampleSeconds(workerMatchers)).isPresent());

    meterRegistry.remove(productionSpeed);

    await()
        .atMost(STORAGE_WAIT)
        .untilAsserted(
            () ->
                assertThat(latestSampleSeconds(PROBES_WAITING))
                    .get()
                    .satisfies(
                        latestExport ->
                            assertThat(
                                    latestExport
                                        - latestSampleSeconds(workerMatchers).orElseThrow())
                                .as(
                                    "seconds from the removed meter's newest sample to the newest"
                                        + " export of streamarr_probes_waiting")
                                .isGreaterThan(LATER_EXPORT_GAP_SECONDS)));
  }

  private static List<String> storedMetricNames() {
    var selector = "{job=\"" + SERVICE + "\"}";
    return query("/api/v1/label/__name__/values?match[]=" + encode(selector))
        .path("data")
        .values()
        .stream()
        .map(JsonNode::asString)
        .toList();
  }

  private static Optional<Double> latestSampleSeconds(String matchers) {
    return sampleSeconds(matchers).stream().max(Double::compare);
  }

  // A range selector returns the stored samples with their own timestamps.
  private static List<Double> sampleSeconds(String matchers) {
    var series = "{job=\"" + SERVICE + "\", " + matchers + "}";
    var result =
        query("/api/v1/query?query=" + encode(series + "[5m]")).path("data").path("result");
    return result.values().stream()
        .flatMap(stored -> stored.path("values").values().stream())
        .map(sample -> sample.get(0).asDouble())
        .sorted()
        .toList();
  }

  private static JsonNode query(String pathAndQuery) {
    var request = HttpRequest.newBuilder(URI.create(prometheusAddress() + pathAndQuery)).build();
    try {
      var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return new ObjectMapper().readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String prometheusAddress() {
    return "http://" + PROMETHEUS.getHost() + ":" + PROMETHEUS.getMappedPort(9090);
  }

  private static GenericContainer<?> startPrometheus() {
    var service = ComposeFile.service("prometheus");
    var command =
        service.path("command").values().stream()
            .map(JsonNode::asString)
            .map(argument -> COMPOSE_DEFAULT.matcher(argument).replaceAll("$1"))
            .toArray(String[]::new);
    var container =
        new GenericContainer<>(service.path("image").asString())
            .withCommand(command)
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/-/ready").forPort(9090));
    container.start();
    return container;
  }

  static class ComposeServerEnvironment
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new SystemEnvironmentPropertySource(
                  "composeServerEnvironment", composeMetricsEnvironment()));
    }

    private static Map<String, Object> composeMetricsEnvironment() {
      var serverEnvironment = ComposeFile.service(SERVICE).path("environment");
      var metrics = new HashMap<String, Object>();
      for (var variable : serverEnvironment.properties()) {
        if (METRICS_VARIABLE.matcher(variable.getKey()).matches()) {
          var value = variable.getValue().asString();
          metrics.put(
              variable.getKey(), value.replace(COMPOSE_PROMETHEUS_ADDRESS, prometheusAddress()));
        }
      }

      return metrics;
    }
  }
}
