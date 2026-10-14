package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.fakes.FakeTaskScheduler;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.graphql.transport.AccessBoundGraphQlWebSocketHandler.AccessBoundGraphQlWebSocketHandlerBuilder;
import io.micrometer.context.ContextSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import reactor.core.publisher.Mono;

@Tag("UnitTest")
@DisplayName("Access-Bound GraphQL WebSocket Handler Tests")
class AccessBoundGraphQlWebSocketHandlerTest {

  private static final String RAW_ACCESS_TOKEN = "raw-access-token-value";

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should snapshot a redacted access token when the handshake interceptors run")
  void shouldSnapshotRedactedAccessTokenWhenHandshakeInterceptorsRun() throws Exception {
    var requestHandler =
        completeBuilder().build().initWebSocketHttpRequestHandler(new DefaultHandshakeHandler());
    SecurityContextHolder.setContext(new SecurityContextImpl(accessTokenAuthentication()));
    var attributes = new HashMap<String, Object>();

    for (var interceptor : requestHandler.getHandshakeInterceptors()) {
      interceptor.beforeHandshake(request(), response(), new TextWebSocketHandler(), attributes);
    }

    SecurityContextHolder.clearContext();
    var snapshot =
        attributes.values().stream()
            .filter(ContextSnapshot.class::isInstance)
            .map(ContextSnapshot.class::cast)
            .findFirst()
            .orElseThrow();
    try (var _ = snapshot.setThreadLocals()) {
      assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials())
          .isInstanceOfSatisfying(
              Jwt.class, jwt -> assertThat(jwt.getTokenValue()).isNotEqualTo(RAW_ACCESS_TOKEN));
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "graphQlHandler",
        "jsonConverter",
        "connectionInitTimeout",
        "keepAlive",
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

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageStartingWith(argument + " ");
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
        "keepAlive",
        builder -> builder.keepAlive(Duration.ofSeconds(20)),
        "allowedOrigins",
        builder -> builder.allowedOrigins(new CorsConfiguration()),
        "clock",
        builder -> builder.clock(new MutableClock()),
        "taskScheduler",
        builder -> builder.taskScheduler(new FakeTaskScheduler()));
  }

  private static StreamarrAuthenticationToken accessTokenAuthentication() {
    var jwt =
        Jwt.withTokenValue(RAW_ACCESS_TOKEN)
            .header("alg", "ES256")
            .subject("account")
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    return new StreamarrAuthenticationToken(
        AuthenticatedIdentityFixture.profileScopedBuilder().build(), jwt, List.of());
  }

  private static ServletServerHttpRequest request() {
    return new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/graphql"));
  }

  private static ServletServerHttpResponse response() {
    return new ServletServerHttpResponse(new MockHttpServletResponse());
  }

  private static AccessBoundGraphQlWebSocketHandlerBuilder completeBuilder() {
    var builder = AccessBoundGraphQlWebSocketHandler.builder();
    requiredArguments().values().forEach(required -> required.accept(builder));
    return builder;
  }
}
