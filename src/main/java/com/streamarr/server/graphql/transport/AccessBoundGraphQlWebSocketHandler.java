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
 * connection and by the bytes it holds for its client. The handshake captures the token's expiry
 * and a redacted copy of the token, and {@link AccessBoundWebSocketHandler} closes the connection
 * at expiry. {@link SendBufferedWebSocketHandler} closes a connection whose client fell behind.
 */
class AccessBoundGraphQlWebSocketHandler extends GraphQlWebSocketHandler {

  private final Clock clock;
  private final TaskScheduler taskScheduler;
  private final SendBufferSettings sendBuffer;

  private AccessBoundGraphQlWebSocketHandler(Arguments arguments) {
    super(
        arguments.graphQlHandler(),
        arguments.jsonConverter(),
        arguments.connectionInitTimeout(),
        arguments.keepAlive(),
        arguments.allowedOrigins());
    this.clock = arguments.clock();
    this.taskScheduler = arguments.taskScheduler();
    this.sendBuffer = arguments.sendBuffer();
  }

  // A factory method, so the null checks run before the framework's constructor.
  @Builder
  private static AccessBoundGraphQlWebSocketHandler create(
      @NonNull WebGraphQlHandler graphQlHandler,
      @NonNull HttpMessageConverter<Object> jsonConverter,
      @NonNull Duration connectionInitTimeout,
      @NonNull Duration keepAlive,
      @NonNull CorsConfiguration allowedOrigins,
      @NonNull Clock clock,
      @NonNull TaskScheduler taskScheduler,
      @NonNull SendBufferSettings sendBuffer) {
    return new AccessBoundGraphQlWebSocketHandler(
        new Arguments(
            graphQlHandler,
            jsonConverter,
            connectionInitTimeout,
            keepAlive,
            allowedOrigins,
            clock,
            taskScheduler,
            sendBuffer));
  }

  private record Arguments(
      WebGraphQlHandler graphQlHandler,
      HttpMessageConverter<Object> jsonConverter,
      Duration connectionInitTimeout,
      Duration keepAlive,
      CorsConfiguration allowedOrigins,
      Clock clock,
      TaskScheduler taskScheduler,
      SendBufferSettings sendBuffer) {}

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
            new AccessBoundWebSocketHandler(
                new SendBufferedWebSocketHandler(this, sendBuffer), clock, taskScheduler),
            handshakeHandler);
    requestHandler.setHandshakeInterceptors(interceptors);
    return requestHandler;
  }
}
