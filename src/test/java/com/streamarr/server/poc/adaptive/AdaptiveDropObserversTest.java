package com.streamarr.server.poc.adaptive;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.Workload;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveFileAdmission;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveLimitAlgorithm;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Throwaway benchmark variant C: a timeout the task catches itself still releases its permit as
 * dropped, because the client and the pool report it on the task's thread.
 */
@Tag("UnitTest")
@ResourceLock("WireMock")
@DisplayName("POC adaptive drop observer tests")
class AdaptiveDropObserversTest {

  private final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AdmissionRuntime runtime = new AdmissionRuntime();
  private final AdaptiveFileAdmission admission =
      new AdaptiveFileAdmission(
          AdaptiveLimitAlgorithm.VEGAS, runtime, Duration.ofMinutes(1), registry);

  @BeforeEach
  void startWireMock() {
    wireMock.start();
  }

  @AfterEach
  void stopWireMock() {
    wireMock.stop();
  }

  private record TimedRequest(String path, Duration timeout) {}

  private double calls(String status) {
    var counter =
        registry.find("poc.adaptive.calls").tag("workload", "scan").tag("status", status).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName("Should release as dropped when the task catches a TMDB request timeout")
  void shouldReleaseAsDroppedWhenTaskCatchesTmdbRequestTimeout() throws Exception {
    wireMock.stubFor(
        get("/3/search/movie").willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
    wireMock.stubFor(get("/3/movie/1").willReturn(aResponse().withStatus(200)));
    var client = new TimeoutObservingHttpClient(HttpClient.newHttpClient());

    var slow = new TimedRequest("/3/search/movie", Duration.ofMillis(100));
    var fast = new TimedRequest("/3/movie/1", Duration.ofSeconds(10));

    var failures =
        admission.processAll(
            Workload.SCAN,
            Stream.of(slow, fast),
            timed -> {
              var request =
                  HttpRequest.newBuilder(URI.create(wireMock.baseUrl() + timed.path()))
                      .timeout(timed.timeout())
                      .build();
              try {
                client.send(request, HttpResponse.BodyHandlers.discarding());
              } catch (IOException _) {
                // The metadata path turns request failures into outcomes, as here.
              }

              return TaskOutcome.WORKED;
            });

    assertThat(failures).isEmpty();
    assertThat(calls("dropped")).isEqualTo(1);
    assertThat(calls("success")).isEqualTo(1);
  }

  @Test
  @DisplayName("Should release as dropped when the task catches a connection pool timeout")
  void shouldReleaseAsDroppedWhenTaskCatchesConnectionPoolTimeout() throws Exception {
    try (var pool = new HikariDataSource()) {
      pool.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/unreachable");
      pool.setConnectionTimeout(250);
      pool.setInitializationFailTimeout(-1);
      var dataSource = new ConnectionTimeoutObservingDataSource(pool);

      var failures =
          admission.processAll(
              Workload.SCAN,
              Stream.of("persist"),
              _ -> {
                try (var _ = dataSource.getConnection()) {
                  return TaskOutcome.WORKED;
                } catch (SQLException _) {
                  // Enrichment catches database failures and marks the file, as here.
                  return TaskOutcome.WORKED;
                }
              });

      assertThat(failures).isEmpty();
      assertThat(calls("dropped")).isEqualTo(1);
      assertThat(dataSource.isWrapperFor(HikariDataSource.class)).isTrue();
      assertThat(dataSource.unwrap(HikariDataSource.class)).isSameAs(pool);
    }
  }
}
