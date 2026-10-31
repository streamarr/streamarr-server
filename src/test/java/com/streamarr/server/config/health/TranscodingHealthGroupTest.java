package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.WorkerVerifiedCodecFamilies;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointWebExtension;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Transcoding Health Group Tests")
class TranscodingHealthGroupTest {

  private static final UUID WORKER_ID = UUID.fromString("0b6f3f43-1d4e-4f43-9b39-3c3d4f6a2e10");

  @Test
  @DisplayName(
      "Should answer 503 and name the worker when a connected worker lacks an encoder that the server needs")
  void shouldAnswer503AndNameTheWorkerWhenAConnectedWorkerLacksAnEncoderThatTheServerNeeds() {
    new WebApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                HealthContributorRegistryAutoConfiguration.class,
                HealthContributorAutoConfiguration.class,
                HealthEndpointAutoConfiguration.class))
        .withUserConfiguration(WorkerWithoutAv1EncoderConfiguration.class)
        .run(
            context -> {
              var groups = context.getBean(HealthEndpointGroups.class);
              var group = groups.get("transcoding");
              assertThat(group).isNotNull();
              assertThat(group.isMember("transcodeExecutor")).isTrue();
              assertThat(group.isMember("tmdb")).isFalse();

              var response =
                  context
                      .getBean(HealthEndpointWebExtension.class)
                      .health(
                          ApiVersion.LATEST,
                          WebServerNamespace.SERVER,
                          SecurityContext.NONE,
                          "transcoding");
              assertThat(response.getStatus()).isEqualTo(503);
              var executorHealth =
                  (IndicatedHealthDescriptor)
                      ((CompositeHealthDescriptor) response.getBody())
                          .getComponents()
                          .get("transcodeExecutor");
              assertThat(executorHealth.getStatus().getCode()).isEqualTo("DEGRADED");
              assertThat(executorHealth.getDetails())
                  .containsEntry(
                      "workersMissingEncoders", Map.of(WORKER_ID.toString(), List.of("av1")));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class WorkerWithoutAv1EncoderConfiguration {

    @Bean
    TranscodeExecutorHealthIndicator transcodeExecutorHealthIndicator() {
      var executor = new FakeTranscodeExecutor();
      executor.setVerifiedVideoCodecFamiliesByWorker(
          List.of(
              WorkerVerifiedCodecFamilies.builder()
                  .workerId(WORKER_ID)
                  .codecFamilies(Set.of("h264"))
                  .build()));
      return new TranscodeExecutorHealthIndicator(executor);
    }

    @Bean
    HealthIndicator tmdbHealthIndicator() {
      return () -> Health.up().build();
    }
  }
}
