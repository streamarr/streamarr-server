package com.streamarr.server.config.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.config.TmdbHttpClientConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Tag("UnitTest")
@ResourceLock("WireMock")
@DisplayName("TMDB Health Indicator Configuration Tests")
class TmdbHealthIndicatorConfigurationTest {

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
  @DisplayName("Should probe configured TMDB API base URL when health queried")
  void shouldProbeConfiguredTmdbApiBaseUrlWhenHealthQueried() {
    wireMock.stubFor(get("/configuration").willReturn(aResponse().withStatus(200)));

    new ApplicationContextRunner()
        .withUserConfiguration(HealthConfiguration.class)
        .withPropertyValues(
            "tmdb.api.base-url=" + wireMock.baseUrl(),
            "tmdb.health.probe-timeout=200ms",
            "tmdb.health.cache-ttl=30s")
        .run(
            context -> {
              var health = context.getBean(TmdbHealthIndicator.class).health();

              assertThat(health.getStatus()).isEqualTo(Status.UP);
              wireMock.verify(1, getRequestedFor(urlEqualTo("/configuration")));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(TmdbHealthProperties.class)
  @Import({TmdbHttpClientConfiguration.class, TmdbHealthIndicator.class})
  static class HealthConfiguration {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
