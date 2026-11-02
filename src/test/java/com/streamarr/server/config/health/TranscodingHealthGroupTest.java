package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.WorkerVerifiedCodecFamilies;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointWebExtension;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the shipped application.yml, so the group under test is the one that ships. The API
 * listener serves this group without authentication, so its answer carries the status alone.
 */
@Tag("UnitTest")
@DisplayName("Transcoding Health Group Tests")
class TranscodingHealthGroupTest {

  private static final UUID WORKER_ID = UUID.fromString("0b6f3f43-1d4e-4f43-9b39-3c3d4f6a2e10");
  private static final String TRANSCODE_EXECUTOR_BEAN = "transcodeExecutorHealthIndicator";

  private static final WebApplicationContextRunner CONTEXT_RUNNER =
      new WebApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(
              AutoConfigurations.of(
                  HealthContributorRegistryAutoConfiguration.class,
                  HealthContributorAutoConfiguration.class,
                  HealthEndpointAutoConfiguration.class))
          .withBean("tmdbHealthIndicator", HealthIndicator.class, () -> () -> Health.up().build());

  @Test
  @DisplayName("Should include the transcode executor alone when the transcoding group loads")
  void shouldIncludeTheTranscodeExecutorAloneWhenTheTranscodingGroupLoads() {
    withWorkerEncoding(Set.of("h264", "av1"))
        .run(
            context -> {
              var group = context.getBean(HealthEndpointGroups.class).get("transcoding");

              assertThat(group).isNotNull();
              assertThat(group.isMember("transcodeExecutor")).isTrue();
              assertThat(group.isMember("tmdb")).isFalse();
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("executorStates")
  @DisplayName(
      "Should answer with the transcode executor's status and its HTTP code when the transcoding group is queried")
  void shouldAnswerWithTheTranscodeExecutorsStatusAndItsHttpCodeWhenTheTranscodingGroupIsQueried(
      ExecutorState state) {
    CONTEXT_RUNNER
        .withBean(
            TRANSCODE_EXECUTOR_BEAN,
            TranscodeExecutorHealthIndicator.class,
            () -> new TranscodeExecutorHealthIndicator(state.executor()))
        .run(
            context -> {
              var response = transcodingGroup(context);

              assertThat(response.getStatus()).isEqualTo(state.expectedHttpStatus());
              assertThat(body(response).path("status").asString())
                  .isEqualTo(state.expectedStatus());
            });
  }

  private static Stream<ExecutorState> executorStates() {
    return Stream.of(
        ExecutorState.builder()
            .name("worker encodes H.264 and AV1")
            .workerCodecFamilies(Set.of("h264", "av1"))
            .expectedStatus("UP")
            .expectedHttpStatus(200)
            .build(),
        ExecutorState.builder()
            .name("worker lacks AV1")
            .workerCodecFamilies(Set.of("h264"))
            .expectedStatus("DEGRADED")
            .expectedHttpStatus(503)
            .build(),
        ExecutorState.builder()
            .name("no worker connected")
            .workerConnected(false)
            .expectedStatus("DOWN")
            .expectedHttpStatus(503)
            .build());
  }

  @Builder
  private record ExecutorState(
      String name,
      boolean workerConnected,
      Set<String> workerCodecFamilies,
      String expectedStatus,
      int expectedHttpStatus) {

    // A builder without workerConnected(false) describes one connected worker.
    private static ExecutorStateBuilder builder() {
      return new ExecutorStateBuilder().workerConnected(true).workerCodecFamilies(Set.of());
    }

    private FakeTranscodeExecutor executor() {
      if (!workerConnected) {
        var executor = new FakeTranscodeExecutor();
        executor.setHealthy(false);
        return executor;
      }

      return executorWithWorkerEncoding(workerCodecFamilies);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  @Test
  @DisplayName("Should answer 503 when the transcode executor is out of service")
  void shouldAnswer503WhenTheTranscodeExecutorIsOutOfService() {
    CONTEXT_RUNNER
        .withBean(
            TRANSCODE_EXECUTOR_BEAN,
            HealthIndicator.class,
            () -> () -> Health.outOfService().build())
        .run(context -> assertThat(transcodingGroup(context).getStatus()).isEqualTo(503));
  }

  @Test
  @DisplayName(
      "Should show the status alone without the worker or its codec family when a connected worker lacks an encoder")
  void
      shouldShowTheStatusAloneWithoutTheWorkerOrItsCodecFamilyWhenAConnectedWorkerLacksAnEncoder() {
    withWorkerEncoding(Set.of("h264"))
        .run(
            context -> {
              var body = body(transcodingGroup(context));

              assertThat(body.has("components")).isFalse();
              assertThat(body.has("details")).isFalse();
              assertThat(body.toString())
                  .doesNotContain(WORKER_ID.toString())
                  .doesNotContain("av1");
            });
  }

  @Test
  @DisplayName("Should keep aggregate health up when a connected worker lacks an encoder")
  void shouldKeepAggregateHealthUpWhenAConnectedWorkerLacksAnEncoder() {
    withWorkerEncoding(Set.of("h264"))
        .run(
            context ->
                assertThat(context.getBean(HealthEndpoint.class).health().getStatus())
                    .isEqualTo(Status.UP));
  }

  private static WebApplicationContextRunner withWorkerEncoding(Set<String> codecFamilies) {
    return CONTEXT_RUNNER.withBean(
        TRANSCODE_EXECUTOR_BEAN,
        TranscodeExecutorHealthIndicator.class,
        () -> new TranscodeExecutorHealthIndicator(executorWithWorkerEncoding(codecFamilies)));
  }

  private static FakeTranscodeExecutor executorWithWorkerEncoding(Set<String> codecFamilies) {
    var executor = new FakeTranscodeExecutor();
    executor.setEncodableVideoCodecFamilies(codecFamilies);
    executor.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(WORKER_ID)
                .codecFamilies(codecFamilies)
                .build()));
    return executor;
  }

  private static WebEndpointResponse<HealthDescriptor> transcodingGroup(
      AssertableWebApplicationContext context) {
    return context
        .getBean(HealthEndpointWebExtension.class)
        .health(ApiVersion.LATEST, WebServerNamespace.SERVER, SecurityContext.NONE, "transcoding");
  }

  private static JsonNode body(WebEndpointResponse<HealthDescriptor> response) {
    var json = new JsonMapper();
    return json.readTree(json.writeValueAsString(response.getBody()));
  }
}
