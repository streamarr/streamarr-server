package com.streamarr.server.graphql.transport;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Captures the authority that opened a WebSocket. It stores the access token's expiry for {@link
 * AccessBoundWebSocketHandler} and, for the rest of the handshake, replaces the security context
 * with one whose token carries the same claims but no raw value. The GraphQL handler snapshots the
 * context during the handshake and keeps it for the connection's lifetime, so the raw access token
 * would otherwise live as long as the socket.
 */
class WebSocketAuthorityHandshakeInterceptor implements HandshakeInterceptor {

  private static final String REDACTED_TOKEN_VALUE = "[redacted]";

  private final ThreadLocal<SecurityContext> requestContext = new ThreadLocal<>();

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    var context = SecurityContextHolder.getContext();
    var opening = openingAccessToken(context);
    if (opening.isEmpty()) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }

    var authentication = opening.get().authentication();
    var jwt = opening.get().jwt();
    attributes.put(AccessBoundWebSocketHandler.ACCESS_EXPIRES_AT, jwt.getExpiresAt());
    requestContext.set(context);
    SecurityContextHolder.setContext(
        new SecurityContextImpl(
            new StreamarrAuthenticationToken(
                authentication.getPrincipal(), redacted(jwt), authentication.getAuthorities())));
    return true;
  }

  private static Optional<OpeningAccessToken> openingAccessToken(SecurityContext context) {
    if (!(context.getAuthentication() instanceof StreamarrAuthenticationToken authentication)) {
      return Optional.empty();
    }

    if (!(authentication.getCredentials() instanceof Jwt jwt)) {
      return Optional.empty();
    }

    if (jwt.getExpiresAt() == null) {
      return Optional.empty();
    }

    return Optional.of(new OpeningAccessToken(authentication, jwt));
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
    var context = requestContext.get();
    if (context == null) {
      return;
    }

    requestContext.remove();
    SecurityContextHolder.setContext(context);
  }

  private static Jwt redacted(Jwt jwt) {
    return Jwt.withTokenValue(REDACTED_TOKEN_VALUE)
        .headers(headers -> headers.putAll(jwt.getHeaders()))
        .claims(claims -> claims.putAll(jwt.getClaims()))
        .build();
  }

  private record OpeningAccessToken(StreamarrAuthenticationToken authentication, Jwt jwt) {}
}
