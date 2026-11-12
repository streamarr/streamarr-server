package com.streamarr.server.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mizosoft.methanol.HttpCache;
import com.github.mizosoft.methanol.Methanol;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.netflix.concurrency.limits.limit.FixedLimit;
import com.streamarr.server.config.health.TmdbHealthProperties;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import com.streamarr.server.services.concurrency.AdaptiveAdmission;
import com.streamarr.server.services.concurrency.AdmissionListener;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import com.streamarr.server.support.BoundedTask;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
  @DisplayName("Should retain rate limiter when enrichment client is configured")
  void shouldRetainRateLimiterWhenEnrichmentClientIsConfigured() throws Exception {
    try (var cache = HttpCache.newBuilder().cacheOnMemory(1024).build()) {
      var client = new TmdbHttpClientConfiguration().tmdbHttpClient(35, 30, cache);

      assertThat(client)
          .isInstanceOfSatisfying(
              Methanol.class,
              methanol ->
                  assertThat(methanol.interceptors())
                      .anyMatch(RateLimitingInterceptor.class::isInstance));
    }
  }

  @Test
  @DisplayName(
      "Should mark the admitted task retried when TMDB answers too many requests then succeeds")
  void shouldMarkTheAdmittedTaskRetriedWhenTmdbAnswersTooManyRequestsThenSucceeds()
      throws Exception {
    wireMock.stubFor(
        get("/movie/1")
            .inScenario("rate limited")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("retried"));
    wireMock.stubFor(
        get("/movie/1")
            .inScenario("rate limited")
            .whenScenarioStateIs("retried")
            .willReturn(aResponse().withStatus(200)));

    var outcome = sendInsideAnAdmittedTask("/movie/1");

    assertThat(outcome.statusCode()).isEqualTo(200);
    assertThat(outcome.report()).isEqualTo(AdmittedTaskReport.DROP);
  }

  @Test
  @DisplayName("Should leave the retry mark unset when TMDB answers at once")
  void shouldLeaveTheRetryMarkUnsetWhenTmdbAnswersAtOnce() throws Exception {
    wireMock.stubFor(get("/movie/2").willReturn(aResponse().withStatus(200)));

    var outcome = sendInsideAnAdmittedTask("/movie/2");

    assertThat(outcome.statusCode()).isEqualTo(200);
    assertThat(outcome.report()).isEqualTo(AdmittedTaskReport.LATENCY);
  }

  private record AdmittedRequestOutcome(int statusCode, AdmittedTaskReport report) {}

  // The task itself reports latency, so a drop can come only from the retry mark.
  private AdmittedRequestOutcome sendInsideAnAdmittedTask(String path) throws Exception {
    var reports = new CopyOnWriteArrayList<AdmittedTaskReport>();
    var statusCode = new AtomicInteger();
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(wireMock.baseUrl() + path))
            .timeout(LOCAL_SERVER_TIMEOUT)
            .GET()
            .build();
    try (var cache = HttpCache.newBuilder().cacheOnMemory(1024).build();
        var admission =
            AdaptiveAdmission.builder()
                .limit(FixedLimit.of(1))
                .nanoClock(System::nanoTime)
                .listener(
                    new AdmissionListener() {
                      @Override
                      public void onReported(AdmittedTaskReport report) {
                        reports.add(report);
                      }
                    })
                .build()) {
      var client = new TmdbHttpClientConfiguration().tmdbHttpClient(1000, 30, cache);

      var failures =
          admission.forkAdmittedTasks(
              List.of(request).iterator(),
              admitted -> {
                statusCode.set(send(client, admitted).statusCode());
                return AdmittedTaskReport.LATENCY;
              },
              Thread.ofVirtual().factory());

      assertThat(failures).isEmpty();
    }

    assertThat(reports).hasSize(1);
    return new AdmittedRequestOutcome(statusCode.get(), reports.getFirst());
  }

  // An admitted task may throw only unchecked exceptions and InterruptedException.
  private static HttpResponse<Void> send(HttpClient client, HttpRequest request)
      throws InterruptedException {
    try {
      return client.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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

  // The context closes each client when the server stops. A client's own close waits for every
  // response body still arriving, and the cached client waits even for a download whose thread
  // was interrupted.
  @ParameterizedTest
  @ValueSource(strings = {"tmdb", "tmdbHealth"})
  @DisplayName(
      "Should end the client's downloads and close at once when the context closes while a"
          + " response body still arrives")
  void shouldEndTheClientsDownloadsAndCloseAtOnceWhenTheContextClosesWhileABodyStillArrives(
      String clientName) throws Exception {
    try (var imageHost = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var context = tmdbClientContext()) {
      imageHost.setSoTimeout((int) LOCAL_SERVER_TIMEOUT.toMillis());
      var client = context.getBean(clientName, HttpClient.class);
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://127.0.0.1:" + imageHost.getLocalPort() + "/poster.jpg"))
              .GET()
              .build();

      try (var download =
              BoundedTask.start(
                  () -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
          var connection = imageHost.accept()) {
        sendHalfOfTheBody(connection);

        BoundedTask.runWithin(LOCAL_SERVER_TIMEOUT, context::close);

        assertThatThrownBy(() -> download.await(LOCAL_SERVER_TIMEOUT))
            .isInstanceOf(IOException.class);
      }
    }
  }

  private static AnnotationConfigApplicationContext tmdbClientContext() {
    var context = new AnnotationConfigApplicationContext();
    context.registerBean(
        TmdbHealthProperties.class,
        () ->
            TmdbHealthProperties.builder()
                .probeTimeout(Duration.ofSeconds(10))
                .cacheTtl(Duration.ofSeconds(30))
                .build());
    context.register(TmdbHttpClientConfiguration.class);
    context.refresh();
    return context;
  }

  // Answers with the headers and half of a cacheable body, then sends nothing more.
  private static void sendHalfOfTheBody(Socket connection) throws IOException {
    var input = connection.getInputStream();
    var ending = 0;
    while (ending < 4) {
      var next = input.read();
      if (next < 0) {
        throw new IOException("The client closed the connection before its request ended");
      }

      ending = (next == '\r' || next == '\n') ? ending + 1 : 0;
    }

    var output = connection.getOutputStream();
    output.write(
        ("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: max-age=60\r\n"
                + "Content-Length: 2048\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    output.write(new byte[1024]);
    output.flush();
  }
}
