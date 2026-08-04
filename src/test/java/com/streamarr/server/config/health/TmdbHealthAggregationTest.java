package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeHttpClient;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the operator decision that TMDB does not gate aggregate health: an unreachable metadata
 * provider degrades enrichment, it does not mean this instance should stop taking traffic or be
 * restarted. The shipped application.yml is loaded rather than restated so the aggregation rules
 * under test are the ones that ship.
 */
@Tag("UnitTest")
@DisplayName("TMDB Health Aggregation Tests")
class TmdbHealthAggregationTest {

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(
              AutoConfigurations.of(
                  HealthContributorRegistryAutoConfiguration.class,
                  HealthContributorAutoConfiguration.class,
                  HealthEndpointAutoConfiguration.class))
          .withUserConfiguration(UnreachableTmdbConfiguration.class)
          .withPropertyValues("tmdb.health.probe-timeout=50ms");

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(TmdbHealthProperties.class)
  static class UnreachableTmdbConfiguration {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean("tmdbHealth")
    HttpClient tmdbHealthHttpClient() {
      return FakeHttpClient.failingWith(new IOException("TMDB unreachable"));
    }

    @Bean
    TmdbHealthIndicator tmdbHealthIndicator(
        @Qualifier("tmdbHealth") HttpClient client, TmdbHealthProperties properties, Clock clock) {
      return new TmdbHealthIndicator(client, properties, clock);
    }
  }

  @Test
  @DisplayName("Should keep aggregate health UP when TMDB is unreachable")
  void shouldKeepAggregateHealthUpWhenTmdbIsUnreachable() {
    CONTEXT_RUNNER.run(
        context -> {
          var health = context.getBean(HealthEndpoint.class).health();

          assertThat(health.getStatus()).isEqualTo(Status.UP);
        });
  }

  @Test
  @DisplayName("Should preserve DOWN when aggregate health includes degraded TMDB")
  void shouldPreserveDownWhenAggregateHealthIncludesDegradedTmdb() {
    CONTEXT_RUNNER.run(
        context -> {
          var primaryGroup = context.getBean(HealthEndpointGroups.class).getPrimary();

          var aggregateStatus =
              primaryGroup
                  .getStatusAggregator()
                  .getAggregateStatus(Set.of(Status.DOWN, Status.UP, TmdbHealthIndicator.DEGRADED));

          assertThat(aggregateStatus).isEqualTo(Status.DOWN);
        });
  }

  @Test
  @DisplayName("Should rank DEGRADED when it is the only primary aggregate status")
  void shouldRankDegradedWhenItIsOnlyPrimaryAggregateStatus() {
    CONTEXT_RUNNER.run(
        context -> {
          var primaryGroup = context.getBean(HealthEndpointGroups.class).getPrimary();

          var aggregateStatus =
              primaryGroup
                  .getStatusAggregator()
                  .getAggregateStatus(Set.of(TmdbHealthIndicator.DEGRADED));

          assertThat(aggregateStatus).isEqualTo(TmdbHealthIndicator.DEGRADED);
        });
  }

  @Test
  @DisplayName("Should configure TMDB reachability when metadata health group loads")
  void shouldConfigureTmdbReachabilityWhenMetadataHealthGroupLoads() {
    CONTEXT_RUNNER.run(
        context -> {
          var group = context.getBean(HealthEndpointGroups.class).get("metadata");

          assertThat(group).isNotNull();
          assertThat(group.isMember("tmdb")).isTrue();
          assertThat(
                  group
                      .getStatusAggregator()
                      .getAggregateStatus(Set.of(TmdbHealthIndicator.DEGRADED)))
              .isEqualTo(TmdbHealthIndicator.DEGRADED);
          assertThat(group.getHttpCodeStatusMapper().getStatusCode(TmdbHealthIndicator.DEGRADED))
              .isEqualTo(503);
        });
  }
}
