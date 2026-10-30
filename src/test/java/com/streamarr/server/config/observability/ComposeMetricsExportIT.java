package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The server's OpenTelemetry settings in {@code docker-compose.yml} must export the server's
 * Micrometer meters over OTLP. The settings reach the server as environment variables, so the test
 * applies them the same way, and only redirects the metrics endpoint to a local receiver.
 */
@Tag("IntegrationTest")
@DisplayName("Compose metrics export integration tests")
@ContextConfiguration(initializers = ComposeMetricsExportIT.ComposeServerEnvironment.class)
class ComposeMetricsExportIT extends AbstractIntegrationTest {

  private static final String METRICS_PATH = "/v1/metrics";
  private static final List<byte[]> EXPORTS = new CopyOnWriteArrayList<>();
  private static final HttpServer RECEIVER = startReceiver();

  @AfterAll
  static void stopReceiver() {
    RECEIVER.stop(0);
  }

  @Test
  @DisplayName("Should export the server's meters over OTLP when it runs with the Compose settings")
  void shouldExportTheServersMetersOverOtlpWhenItRunsWithTheComposeSettings() {
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                assertThat(EXPORTS)
                    .anySatisfy(
                        export ->
                            assertThat(new String(export, StandardCharsets.ISO_8859_1))
                                .contains("streamarr.probes.waiting")
                                .contains("hikaricp.connections.active")));
  }

  private static HttpServer startReceiver() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          METRICS_PATH,
          exchange -> {
            try (exchange) {
              EXPORTS.add(exchange.getRequestBody().readAllBytes());
              exchange.sendResponseHeaders(200, -1);
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static class ComposeServerEnvironment
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var environment = new HashMap<String, Object>(composeOpenTelemetryEnvironment());
      environment.put(
          "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
          "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + METRICS_PATH);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new SystemEnvironmentPropertySource("composeServerEnvironment", environment));
    }

    private static Map<String, String> composeOpenTelemetryEnvironment() {
      try {
        JsonNode compose =
            new ObjectMapper()
                .valueToTree(new Yaml().load(Files.readString(Path.of("docker-compose.yml"))));
        var serverEnvironment =
            compose.path("services").path("streamarr-server").path("environment");
        var openTelemetry = new HashMap<String, String>();
        for (var variable : serverEnvironment.properties()) {
          if (variable.getKey().startsWith("OTEL_")) {
            openTelemetry.put(variable.getKey(), variable.getValue().asString());
          }
        }

        return openTelemetry;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
