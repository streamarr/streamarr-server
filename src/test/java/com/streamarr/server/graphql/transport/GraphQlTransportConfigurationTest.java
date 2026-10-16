package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.CanonicalBaseUrl;
import com.streamarr.server.fakes.FakeTaskScheduler;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.graphql.subscriptions.SubscriptionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.graphql.autoconfigure.GraphQlProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("GraphQL Transport Configuration Tests")
class GraphQlTransportConfigurationTest {

  private static final String KEEP_ALIVE = "spring.graphql.websocket.keep-alive";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              GraphQlTransportConfiguration.class, GraphQlPropertiesConfiguration.class)
          .withBean(
              WebGraphQlHandler.class, () -> WebGraphQlHandler.builder(_ -> Mono.empty()).build())
          .withBean(JsonMapper.class, JsonMapper::new)
          .withBean(CanonicalBaseUrl.class, CanonicalBaseUrl::absent)
          .withBean(Clock.class, MutableClock::new)
          .withBean(TaskScheduler.class, FakeTaskScheduler::new)
          .withBean(
              SubscriptionProperties.class,
              () ->
                  new SubscriptionProperties(
                      64, 32, DataSize.ofMegabytes(1), Duration.ofSeconds(10)))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  @DisplayName("Should create the WebSocket handler when a keep-alive is configured")
  void shouldCreateWebSocketHandlerWhenKeepAliveIsConfigured() {
    contextRunner
        .withPropertyValues(KEEP_ALIVE + "=20s")
        .run(context -> assertThat(context).hasSingleBean(GraphQlWebSocketHandler.class));
  }

  @Test
  @DisplayName("Should fail startup naming the keep-alive setting when the keep-alive is missing")
  void shouldFailStartupNamingKeepAliveSettingWhenKeepAliveIsMissing() {
    contextRunner
        .withPropertyValues(KEEP_ALIVE + "=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOfSatisfying(
                      InvalidConfigurationPropertyValueException.class,
                      invalid -> assertThat(invalid.getName()).isEqualTo(KEEP_ALIVE));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(GraphQlProperties.class)
  static class GraphQlPropertiesConfiguration {}
}
