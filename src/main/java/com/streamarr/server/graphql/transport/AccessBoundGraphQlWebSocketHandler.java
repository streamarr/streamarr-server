package com.streamarr.server.graphql.transport;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * The graphql-transport-ws handler on /graphql, bounded by the access token that opened each
 * connection. The handshake captures the token's expiry and a redacted copy of the token, and
 * {@link AccessBoundWebSocketHandler} closes the connection at expiry.
 */
class AccessBoundGraphQlWebSocketHandler extends GraphQlWebSocketHandler {

  private final Clock clock;
  private final TaskScheduler taskScheduler;

  @Builder
  private AccessBoundGraphQlWebSocketHandler(
      WebGraphQlHandler graphQlHandler,
      HttpMessageConverter<Object> jsonConverter,
      @NonNull Duration connectionInitTimeout,
      Duration keepAlive,
      @NonNull CorsConfiguration allowedOrigins,
      @NonNull Clock clock,
      @NonNull TaskScheduler taskScheduler) {
    super(graphQlHandler, jsonConverter, connectionInitTimeout, keepAlive, allowedOrigins);
    this.clock = clock;
    this.taskScheduler = taskScheduler;
  }

  @Override
  public WebSocketHttpRequestHandler initWebSocketHttpRequestHandler(
      HandshakeHandler handshakeHandler) {
    var interceptors =
        new ArrayList<>(
            super.initWebSocketHttpRequestHandler(handshakeHandler).getHandshakeInterceptors());
    // First, so the context snapshot the framework's interceptor takes holds the redacted token.
    interceptors.addFirst(new WebSocketAuthorityHandshakeInterceptor());
    var requestHandler =
        new WebSocketHttpRequestHandler(
            new AccessBoundWebSocketHandler(this, clock, taskScheduler), handshakeHandler);
    requestHandler.setHandshakeInterceptors(interceptors);
    return requestHandler;
  }
}
