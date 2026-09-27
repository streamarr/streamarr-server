package com.streamarr.server.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.HttpCache;
import com.github.mizosoft.methanol.Methanol;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.config.http.PocSeparateImageDownloader;
import com.streamarr.server.config.http.RateLimitingInterceptor;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.TaskOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Throwaway benchmark levers: where the TMDB gate sits, and images on their own client. */
@Tag("UnitTest")
@ResourceLock("WireMock")
@DisplayName("POC TMDB lever tests")
class PocTmdbLeversTest {

  private final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
  private final PacedTmdbGate gate = new PacedTmdbGate(35, Duration.ofSeconds(1));
  private HttpCache cache;

  @BeforeEach
  void start() {
    wireMock.start();
    cache = HttpCache.newBuilder().cacheOnMemory(1024 * 1024).build();
  }

  @AfterEach
  void stop() throws Exception {
    cache.close();
    wireMock.stop();
  }

  private Methanol clientGateClient() {
    return (Methanol)
        new TmdbHttpClientConfiguration()
            .tmdbPacedHttpClient(gate, wireMock.baseUrl() + "/t/p/original", 5, "fifo", cache);
  }

  private Methanol backendGateClient() {
    return PocTmdbLimiterPosition.withGateAsBackendInterceptor(clientGateClient());
  }

  private HttpRequest request(String path) {
    return HttpRequest.newBuilder(URI.create(wireMock.baseUrl() + path)).build();
  }

  private void stubCacheableMovie() {
    wireMock.stubFor(
        get("/3/movie/1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Cache-Control", "public, max-age=600")
                    .withBody("{}")));
  }

  private static void sendTwice(HttpClient client, HttpRequest request) throws Exception {
    client.send(request, HttpResponse.BodyHandlers.ofString());
    client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Should spend a permit on a cache hit when the gate is a client interceptor")
  void shouldSpendPermitOnCacheHitWhenGateIsClientInterceptor() throws Exception {
    stubCacheableMovie();

    sendTwice(clientGateClient(), request("/3/movie/1"));

    wireMock.verify(1, getRequestedFor(urlEqualTo("/3/movie/1")));
    assertThat(gate.apiRequests()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should spend no permit on a cache hit when the gate is a backend interceptor")
  void shouldSpendNoPermitOnCacheHitWhenGateIsBackendInterceptor() throws Exception {
    stubCacheableMovie();
    var client = backendGateClient();

    sendTwice(client, request("/3/movie/1"));

    wireMock.verify(1, getRequestedFor(urlEqualTo("/3/movie/1")));
    assertThat(gate.apiRequests()).isEqualTo(1);
    assertThat(client.interceptors()).noneMatch(RateLimitingInterceptor.class::isInstance);
    assertThat(client.backendInterceptors())
        .singleElement()
        .isInstanceOf(RateLimitingInterceptor.class);
    assertThat(client.cache()).containsSame(cache);
  }

  @Test
  @DisplayName("Should spend a permit on a revalidation when the gate is a backend interceptor")
  void shouldSpendPermitOnRevalidationWhenGateIsBackendInterceptor() throws Exception {
    wireMock.stubFor(
        get("/3/movie/2")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Cache-Control", "public, max-age=0")
                    .withHeader("ETag", "\"v1\"")
                    .withBody("{}")));
    wireMock.stubFor(
        get("/3/movie/2")
            .withHeader("If-None-Match", equalTo("\"v1\""))
            .atPriority(1)
            .willReturn(aResponse().withStatus(304).withHeader("ETag", "\"v1\"")));

    sendTwice(backendGateClient(), request("/3/movie/2"));

    wireMock.verify(2, getRequestedFor(urlEqualTo("/3/movie/2")));
    assertThat(gate.apiRequests()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should re-acquire a permit per retry and signal the task when the gate is a backend interceptor")
  void shouldReacquirePermitPerRetryWhenGateIsBackendInterceptor() throws Exception {
    wireMock.stubFor(
        get("/3/movie/3")
            .inScenario("retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("retried"));
    wireMock.stubFor(
        get("/3/movie/3")
            .inScenario("retry")
            .whenScenarioStateIs("retried")
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    var client = backendGateClient();
    var runtime = new AdmissionRuntime();
    var ticket = runtime.admit();

    var outcome =
        ticket.run(
            _ -> {
              client.send(request("/3/movie/3"), HttpResponse.BodyHandlers.discarding());
              return TaskOutcome.WORKED;
            },
            "file");

    assertThat(outcome).isEqualTo(TaskOutcome.WORKED);
    wireMock.verify(2, getRequestedFor(urlEqualTo("/3/movie/3")));
    assertThat(gate.apiRequests()).isEqualTo(2);
    assertThat(gate.retries()).isEqualTo(1);
    assertThat(ticket.tmdbApiReservations()).isEqualTo(2);
    assertThat(ticket.tmdbRetried()).isTrue();
  }

  @Test
  @DisplayName(
      "Should download images past a saturated shared gate when images use their own client")
  void shouldDownloadImagesPastSaturatedSharedGateWhenImagesUseTheirOwnClient() throws Exception {
    wireMock.stubFor(
        get("/t/p/original/a.jpg").willReturn(aResponse().withStatus(200).withBody("img")));
    var tmdb = clientGateClient();
    for (var i = 0; i < 35 * 11; i++) {
      gate.reserveNanos();
    }
    var sharedBacklogBefore = gate.backlogNanos();

    try (var downloader =
        new PocSeparateImageDownloader(
            tmdb,
            gate,
            wireMock.baseUrl() + "/t/p/original",
            35,
            "client",
            "fifo",
            new SimpleMeterRegistry())) {
      var startedNanos = System.nanoTime();
      var body = downloader.downloadImage("/a.jpg");
      var elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

      assertThat(body).asString().isEqualTo("img");
      assertThat(sharedBacklogBefore).isGreaterThan(Duration.ofSeconds(9).toNanos());
      assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
      assertThat(gate.backlogNanos()).isLessThanOrEqualTo(sharedBacklogBefore);
      assertThat(gate.imageRequests()).isEqualTo(1);
      assertThat(gate.apiRequests()).isZero();
      wireMock.verify(1, getRequestedFor(urlEqualTo("/t/p/original/a.jpg")));
    }
  }

  @Test
  @DisplayName("Should retry a 429 image download and count it when images use their own client")
  void shouldRetryImageDownloadWhenImagesUseTheirOwnClient() throws Exception {
    wireMock.stubFor(
        get("/t/p/original/b.jpg")
            .inScenario("image-retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("retried"));
    wireMock.stubFor(
        get("/t/p/original/b.jpg")
            .inScenario("image-retry")
            .whenScenarioStateIs("retried")
            .willReturn(aResponse().withStatus(200).withBody("img")));

    try (var downloader =
        new PocSeparateImageDownloader(
            backendGateClient(),
            gate,
            wireMock.baseUrl() + "/t/p/original",
            35,
            "backend",
            "fifo",
            new SimpleMeterRegistry())) {
      var body = downloader.downloadImage("/b.jpg");

      assertThat(body).asString().isEqualTo("img");
      wireMock.verify(2, getRequestedFor(urlEqualTo("/t/p/original/b.jpg")));
      assertThat(gate.imageRequests()).isEqualTo(2);
      assertThat(gate.retries()).isEqualTo(1);
      assertThat(gate.apiRequests()).isZero();
    }
  }
}
