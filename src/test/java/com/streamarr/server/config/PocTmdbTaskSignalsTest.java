package com.streamarr.server.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.HttpCache;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.TaskOutcome;
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

/** Throwaway benchmark infra: per-task TMDB signals reach the admitted task that sent the request. */
@Tag("UnitTest")
@ResourceLock("WireMock")
@DisplayName("POC TMDB task signal tests")
class PocTmdbTaskSignalsTest {

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
  @DisplayName("Should flag the task and leave transit when its TMDB request is retried after 429")
  void shouldFlagTaskAndLeaveTransitWhenItsTmdbRequestIsRetried() throws Exception {
    wireMock.stubFor(
        get("/3/movie/1")
            .inScenario("retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("retried"));
    wireMock.stubFor(
        get("/3/movie/1").inScenario("retry").whenScenarioStateIs("retried").willReturn(aResponse().withStatus(200)));
    var gate = new PacedTmdbGate(35, Duration.ofSeconds(1));
    var runtime = new AdmissionRuntime();

    try (var cache = HttpCache.newBuilder().cacheOnMemory(1024).build()) {
      var client =
          new TmdbHttpClientConfiguration()
              .tmdbPacedHttpClient(gate, wireMock.baseUrl() + "/t/p/original", 5, "fifo", cache);
      var request = HttpRequest.newBuilder(URI.create(wireMock.baseUrl() + "/3/movie/1")).build();
      var ticket = runtime.admit();
      var inTransitBeforeRequest = runtime.inTransit();

      var outcome =
          ticket.run(
              _ -> {
                client.send(request, HttpResponse.BodyHandlers.discarding());
                return TaskOutcome.WORKED;
              },
              "file");

      assertThat(inTransitBeforeRequest).isEqualTo(1);
      assertThat(outcome).isEqualTo(TaskOutcome.WORKED);
      assertThat(ticket.tmdbRetried()).isTrue();
      assertThat(ticket.tmdbApiReservations()).isEqualTo(2);
      assertThat(runtime.inTransit()).isZero();
      assertThat(runtime.inFlight()).isZero();
      assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(1);
      assertThat(gate.retries()).isEqualTo(1);
      assertThat(gate.apiRequests()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("Should count an image request without a task when artwork runs off the task thread")
  void shouldCountImageRequestWithoutTaskWhenArtworkRunsOffTaskThread() throws Exception {
    wireMock.stubFor(get("/t/p/original/a.jpg").willReturn(aResponse().withStatus(200)));
    var gate = new PacedTmdbGate(35, Duration.ofSeconds(1));
    var runtime = new AdmissionRuntime();

    try (var cache = HttpCache.newBuilder().cacheOnMemory(1024).build()) {
      var client =
          new TmdbHttpClientConfiguration()
              .tmdbPacedHttpClient(gate, wireMock.baseUrl() + "/t/p/original", 5, "fifo", cache);
      var ticket = runtime.admit();

      client.send(
          HttpRequest.newBuilder(URI.create(wireMock.baseUrl() + "/t/p/original/a.jpg")).build(),
          HttpResponse.BodyHandlers.discarding());

      assertThat(gate.imageRequests()).isEqualTo(1);
      assertThat(gate.apiRequests()).isZero();
      assertThat(ticket.tmdbImageReservations()).isZero();
      assertThat(runtime.inTransit()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("Should count a failed task and leave transit when the task throws before TMDB")
  void shouldCountFailedTaskAndLeaveTransitWhenTaskThrowsBeforeTmdb() {
    var runtime = new AdmissionRuntime();
    var ticket = runtime.admit();

    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                ticket.run(
                    _ -> {
                      throw new IllegalStateException("boom");
                    },
                    "file"));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(runtime.failedCount()).isEqualTo(1);
    assertThat(runtime.inTransit()).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(ticket.tmdbRetried()).isFalse();
  }
  @Test
  @DisplayName("Should hold a secondary artwork image behind queued core work when secondary yields")
  void shouldHoldSecondaryArtworkImageBehindQueuedCoreWorkWhenSecondaryYields() throws Exception {
    wireMock.stubFor(get("/t/p/original/p.jpg").willReturn(aResponse().withStatus(200).withBody("img")));
    var gate = new PacedTmdbGate(100, Duration.ZERO);

    try (var cache = HttpCache.newBuilder().cacheOnMemory(1024).build()) {
      var client =
          new TmdbHttpClientConfiguration()
              .tmdbPacedHttpClient(gate, wireMock.baseUrl() + "/t/p/original", 5, "yield", cache);
      var image = HttpRequest.newBuilder(URI.create(wireMock.baseUrl() + "/t/p/original/p.jpg")).build();
      for (var i = 0; i < 30; i++) {
        gate.reserveNanos();
      }
      var coreBacklog = gate.backlogNanos();

      // The artwork fetcher downloads on a child virtual thread of the secondary-artwork thread.
      var started = System.nanoTime();
      var status =
          com.streamarr.server.services.library.admission.TmdbTaskSignals.callAsSecondaryArtwork(
              () -> {
                try (var downloads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                  return downloads
                      .submit(() -> client.send(image, HttpResponse.BodyHandlers.discarding()).statusCode())
                      .get();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      var waited = System.nanoTime() - started;

      assertThat(status).isEqualTo(200);
      assertThat(coreBacklog).isGreaterThan(Duration.ofMillis(250).toNanos());
      assertThat(waited).isGreaterThanOrEqualTo(coreBacklog - Duration.ofMillis(20).toNanos());
      assertThat(gate.backgroundRequests()).isEqualTo(1);
      assertThat(gate.imageRequests()).isEqualTo(1);
    }
  }
}
