package com.streamarr.server.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.Methanol;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.config.health.TmdbHealthProperties;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@Tag("UnitTest")
@ResourceLock("WireMock")
@DisplayName("TMDB HTTP Client Configuration Tests")
class TmdbHttpClientConfigurationTest {

  private static final Duration PROBE_TIMEOUT = Duration.ofMillis(200);
  private static final Duration LOCAL_SERVER_TIMEOUT = Duration.ofSeconds(2);

  private final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

  @BeforeEach
  void startWireMock() {
    wireMock.start();
  }

  @AfterEach
  void stopWireMock() {
    wireMock.stop();
  }

  @Test
  @DisplayName("Should return first retryable response when health client receives 429")
  void shouldReturnFirstRetryableResponseWhenHealthClientReceives429() throws Exception {
    wireMock.stubFor(
        get("/configuration")
            .inScenario("retry detection")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("retried"));
    wireMock.stubFor(
        get("/configuration")
            .inScenario("retry detection")
            .whenScenarioStateIs("retried")
            .willReturn(aResponse().withStatus(200)));
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(LOCAL_SERVER_TIMEOUT)
            .cacheTtl(Duration.ofSeconds(30))
            .build();
    var client = new TmdbHttpClientConfiguration().tmdbHealthHttpClient(properties);
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(wireMock.baseUrl() + "/configuration"))
            .timeout(LOCAL_SERVER_TIMEOUT)
            .GET()
            .build();

    var response = client.send(request, HttpResponse.BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(429);
  }

  @Test
  @DisplayName("Should use health probe timeout when configuring connection establishment")
  void shouldUseHealthProbeTimeoutWhenConfiguringConnectionEstablishment() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(PROBE_TIMEOUT)
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    var client = new TmdbHttpClientConfiguration().tmdbHealthHttpClient(properties);

    assertThat(client.connectTimeout()).contains(PROBE_TIMEOUT);
  }

  @Test
  @DisplayName("Should use health probe timeout when configuring request deadline")
  void shouldUseHealthProbeTimeoutWhenConfiguringRequestDeadline() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(PROBE_TIMEOUT)
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    var client = new TmdbHttpClientConfiguration().tmdbHealthHttpClient(properties);

    assertThat(client)
        .isInstanceOfSatisfying(
            Methanol.class,
            methanol -> assertThat(methanol.requestTimeout()).contains(PROBE_TIMEOUT));
  }

  @Test
  @DisplayName("Should omit rate limiter when health client is configured")
  void shouldOmitRateLimiterWhenHealthClientIsConfigured() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(PROBE_TIMEOUT)
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    var client = new TmdbHttpClientConfiguration().tmdbHealthHttpClient(properties);

    assertThat(client)
        .isInstanceOfSatisfying(
            Methanol.class,
            methanol ->
                assertThat(methanol.interceptors())
                    .noneMatch(RateLimitingInterceptor.class::isInstance));
  }

  @Test
  @DisplayName("Should fetch each response when health server supplies cache headers")
  void shouldFetchEachResponseWhenHealthServerSuppliesCacheHeaders() throws Exception {
    wireMock.stubFor(
        get("/configuration")
            .inScenario("reachability changes")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(200).withHeader("Cache-Control", "max-age=60"))
            .willSetStateTo("unavailable"));
    wireMock.stubFor(
        get("/configuration")
            .inScenario("reachability changes")
            .whenScenarioStateIs("unavailable")
            .willReturn(aResponse().withStatus(503)));
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(LOCAL_SERVER_TIMEOUT)
            .cacheTtl(Duration.ofSeconds(30))
            .build();
    var client = new TmdbHttpClientConfiguration().tmdbHealthHttpClient(properties);
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(wireMock.baseUrl() + "/configuration"))
            .timeout(LOCAL_SERVER_TIMEOUT)
            .GET()
            .build();

    var firstResponse = client.send(request, HttpResponse.BodyHandlers.discarding());
    var secondResponse = client.send(request, HttpResponse.BodyHandlers.discarding());

    assertThat(firstResponse.statusCode()).isEqualTo(200);
    assertThat(secondResponse.statusCode()).isEqualTo(503);
  }
}
