package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeTranscodeExecutor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Worker Availability Probe Configuration Tests")
class WorkerAvailabilityProbesTest {

  @Test
  @DisplayName("Should keep server liveness and readiness up when no worker is available")
  void shouldKeepServerLivenessAndReadinessUpWhenNoWorkerIsAvailable() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                ApplicationAvailabilityAutoConfiguration.class,
                HealthContributorRegistryAutoConfiguration.class,
                HealthContributorAutoConfiguration.class,
                HealthEndpointAutoConfiguration.class,
                AvailabilityProbesAutoConfiguration.class))
        .withUserConfiguration(UnavailableWorkerConfiguration.class)
        .withPropertyValues("spring.main.cloud-platform=kubernetes")
        .run(
            context -> {
              AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
              AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
              assertThat(
                      context.getBean(TranscodeExecutorHealthIndicator.class).health().getStatus())
                  .isEqualTo(Status.DOWN);
              var endpoint = context.getBean(HealthEndpoint.class);
              var groups = context.getBean(HealthEndpointGroups.class);

              for (var probe : List.of("liveness", "readiness")) {
                assertThat(groups.get(probe)).isNotNull();
                assertThat(groups.get(probe).isMember("transcodeExecutor")).isFalse();
                assertThat(endpoint.healthForPath(probe).getStatus()).isEqualTo(Status.UP);
              }
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class UnavailableWorkerConfiguration {

    @Bean
    TranscodeExecutorHealthIndicator transcodeExecutorHealthIndicator() {
      var executor = new FakeTranscodeExecutor();
      executor.setHealthy(false);
      executor.setAvailableSlots(0);
      return new TranscodeExecutorHealthIndicator(executor);
    }

    @Bean
    HealthIndicator tmdbHealthIndicator() {
      return () -> Health.up().build();
    }
  }
}
