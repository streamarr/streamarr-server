package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeHttpClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Tag("UnitTest")
@DisplayName("TMDB Health Indicator Configuration Tests")
class TmdbHealthIndicatorConfigurationTest {

  private static final String TMDB_API_BASE_URL = "https://tmdb.test/3";
  private static final URI CONFIGURATION_URI = URI.create(TMDB_API_BASE_URL + "/configuration");

  @Test
  @DisplayName("Should probe configured TMDB API base URL when health queried")
  void shouldProbeConfiguredTmdbApiBaseUrlWhenHealthQueried() {
    new ApplicationContextRunner()
        .withUserConfiguration(HealthConfiguration.class)
        .withPropertyValues(
            "tmdb.api.base-url=" + TMDB_API_BASE_URL,
            "tmdb.health.probe-timeout=200ms",
            "tmdb.health.cache-ttl=30s")
        .run(
            context -> {
              var client = context.getBean(FakeHttpClient.class);

              context.getBean(TmdbHealthIndicator.class).health();

              assertThat(client.sentRequests())
                  .singleElement()
                  .extracting(HttpRequest::uri)
                  .isEqualTo(CONFIGURATION_URI);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(TmdbHealthProperties.class)
  @Import(TmdbHealthIndicator.class)
  static class HealthConfiguration {

    @Bean("tmdbHealth")
    FakeHttpClient tmdbHealthHttpClient() {
      return FakeHttpClient.respondingWith(200);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
