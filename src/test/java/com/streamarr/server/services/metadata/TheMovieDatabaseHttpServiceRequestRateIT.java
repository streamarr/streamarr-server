package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.config.TmdbHttpClientConfiguration;
import com.streamarr.server.properties.TmdbHealthProperties;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@ResourceLock("WireMock")
@SpringBootTest(
    classes = {
      TmdbHttpClientConfiguration.class,
      TheMovieDatabaseHttpService.class,
      TheMovieDatabaseHttpServiceRequestRateIT.ServiceCollaborators.class
    },
    properties = "tmdb.api.requests-per-second=1")
@DisplayName("TMDB HTTP Service Request Rate Integration Tests")
class TheMovieDatabaseHttpServiceRequestRateIT {

  private static final byte[] IMAGE_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
  private static final int IMAGE_BURST = 30;
  // At one request a second the burst would need at least 28 s.
  private static final Duration BURST_BOUND = Duration.ofSeconds(20);

  private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    wireMock.start();
  }

  @Autowired private TheMovieDatabaseHttpService service;

  @DynamicPropertySource
  static void configureHosts(DynamicPropertyRegistry registry) {
    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.image.base-url", wireMock::baseUrl);
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void resetStubs() {
    wireMock.resetAll();
  }

  @Test
  @DisplayName(
      "Should download a burst of images at once when metadata calls are held to one a second")
  void shouldDownloadABurstOfImagesAtOnceWhenMetadataCallsAreHeldToOneASecond() throws Exception {
    wireMock.stubFor(
        get(urlPathMatching("/burst-\\d+\\.jpg"))
            .willReturn(aResponse().withStatus(200).withBody(IMAGE_BYTES)));
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"page": 1, "results": [], "total_results": 0, "total_pages": 0}
                        """)));

    List<Future<byte[]>> downloads;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      downloads = executor.invokeAll(imageBurst(), BURST_BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    assertThat(downloads)
        .extracting(Future::state)
        .as("downloads finished within %s", BURST_BOUND)
        .containsOnly(Future.State.SUCCESS);
    assertThat(downloads)
        .extracting(Future::resultNow)
        .allSatisfy(image -> assertThat(image).isEqualTo(IMAGE_BYTES));

    var metadataStart = System.nanoTime();
    for (var call = 0; call < 3; call++) {
      service.searchForMovie(VideoFileParserResult.builder().title("Any").build());
    }

    // The limiter lets two calls through at once: one stored permit, and one charged to the next.
    assertThat(Duration.ofNanos(System.nanoTime() - metadataStart))
        .as("three metadata calls at one a second")
        .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
  }

  private List<Callable<byte[]>> imageBurst() {
    return IntStream.range(0, IMAGE_BURST)
        .<Callable<byte[]>>mapToObj(
            image -> () -> service.downloadImage("/burst-" + image + ".jpg"))
        .toList();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(TmdbHealthProperties.class)
  static class ServiceCollaborators {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
