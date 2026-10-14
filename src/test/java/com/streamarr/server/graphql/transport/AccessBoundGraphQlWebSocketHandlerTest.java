package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeTaskScheduler;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.graphql.transport.AccessBoundGraphQlWebSocketHandler.AccessBoundGraphQlWebSocketHandlerBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import reactor.core.publisher.Mono;

@Tag("UnitTest")
@DisplayName("Access-Bound GraphQL WebSocket Handler Tests")
class AccessBoundGraphQlWebSocketHandlerTest {

  @Test
  @DisplayName("Should capture the opening authority before any other handshake interceptor runs")
  void shouldCaptureOpeningAuthorityBeforeAnyOtherHandshakeInterceptorRuns() {
    var requestHandler =
        completeBuilder().build().initWebSocketHttpRequestHandler(new DefaultHandshakeHandler());

    assertThat(requestHandler.getHandshakeInterceptors())
        .first()
        .isInstanceOf(WebSocketAuthorityHandshakeInterceptor.class);
    assertThat(decoratorsOf(requestHandler.getWebSocketHandler()))
        .contains(AccessBoundWebSocketHandler.class);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "graphQlHandler",
        "jsonConverter",
        "connectionInitTimeout",
        "allowedOrigins",
        "clock",
        "taskScheduler"
      })
  @DisplayName("Should refuse to build when a required argument is missing")
  void shouldRefuseToBuildWhenRequiredArgumentIsMissing(String argument) {
    var builder = AccessBoundGraphQlWebSocketHandler.builder();
    requiredArguments().entrySet().stream()
        .filter(required -> !required.getKey().equals(argument))
        .forEach(required -> required.getValue().accept(builder));

    // The framework's constructor checks the handler and converter before the subclass runs.
    assertThatThrownBy(builder::build)
        .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
  }

  private static List<Class<?>> decoratorsOf(WebSocketHandler handler) {
    var decorators = new ArrayList<Class<?>>();
    var current = handler;
    while (current instanceof WebSocketHandlerDecorator decorator) {
      decorators.add(decorator.getClass());
      current = decorator.getDelegate();
    }

    return decorators;
  }

  private static Map<String, Consumer<AccessBoundGraphQlWebSocketHandlerBuilder>>
      requiredArguments() {
    return Map.of(
        "graphQlHandler",
        builder -> builder.graphQlHandler(WebGraphQlHandler.builder(_ -> Mono.empty()).build()),
        "jsonConverter",
        builder -> builder.jsonConverter(new JacksonJsonHttpMessageConverter()),
        "connectionInitTimeout",
        builder -> builder.connectionInitTimeout(Duration.ofSeconds(10)),
        "allowedOrigins",
        builder -> builder.allowedOrigins(new CorsConfiguration()),
        "clock",
        builder -> builder.clock(new MutableClock()),
        "taskScheduler",
        builder -> builder.taskScheduler(new FakeTaskScheduler()));
  }

  private static AccessBoundGraphQlWebSocketHandlerBuilder completeBuilder() {
    var builder = AccessBoundGraphQlWebSocketHandler.builder().keepAlive(Duration.ofSeconds(20));
    requiredArguments().values().forEach(required -> required.accept(builder));
    return builder;
  }
}
