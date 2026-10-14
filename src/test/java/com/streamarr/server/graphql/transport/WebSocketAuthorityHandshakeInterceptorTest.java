package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Tag("UnitTest")
@DisplayName("WebSocket Authority Handshake Interceptor Tests")
class WebSocketAuthorityHandshakeInterceptorTest {

  private static final String RAW_ACCESS_TOKEN = "raw-access-token-value";
  private static final Instant ACCESS_EXPIRES_AT = Instant.parse("2026-01-01T00:10:00Z");

  private final WebSocketAuthorityHandshakeInterceptor interceptor =
      new WebSocketAuthorityHandshakeInterceptor();
  private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();
  private final ServletServerHttpRequest request =
      new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/graphql"));
  private final ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should keep the identity and expiry but not the raw token while the handshake runs")
  void shouldKeepIdentityAndExpiryButNotRawTokenWhileHandshakeRuns() {
    var authentication = accessTokenAuthentication();
    SecurityContextHolder.setContext(new SecurityContextImpl(authentication));
    var attributes = new HashMap<String, Object>();

    var accepted =
        interceptor.beforeHandshake(request, response, new TextWebSocketHandler(), attributes);

    assertThat(accepted).isTrue();
    assertThat(attributes)
        .containsEntry(AccessBoundWebSocketHandler.ACCESS_EXPIRES_AT, ACCESS_EXPIRES_AT);
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isInstanceOfSatisfying(
            StreamarrAuthenticationToken.class,
            captured -> {
              assertThat(captured.getPrincipal()).isEqualTo(authentication.getPrincipal());
              assertThat(captured.getAuthorities()).isEqualTo(authentication.getAuthorities());
              assertThat(captured.getCredentials())
                  .isInstanceOfSatisfying(
                      Jwt.class,
                      jwt -> {
                        assertThat(jwt.getTokenValue()).isNotEqualTo(RAW_ACCESS_TOKEN);
                        assertThat(jwt.getExpiresAt()).isEqualTo(ACCESS_EXPIRES_AT);
                      });
            });
  }

  @Test
  @DisplayName("Should restore the request's security context after the handshake")
  void shouldRestoreRequestSecurityContextAfterHandshake() {
    var original = new SecurityContextImpl(accessTokenAuthentication());
    SecurityContextHolder.setContext(original);
    interceptor.beforeHandshake(request, response, new TextWebSocketHandler(), new HashMap<>());

    interceptor.afterHandshake(request, response, new TextWebSocketHandler(), null);

    assertThat(SecurityContextHolder.getContext()).isSameAs(original);
  }

  @Test
  @DisplayName("Should refuse the handshake with 401 when no access token authenticated it")
  void shouldRefuseHandshakeWith401WhenNoAccessTokenAuthenticatedIt() {
    var attributes = new HashMap<String, Object>();

    var accepted =
        interceptor.beforeHandshake(request, response, new TextWebSocketHandler(), attributes);

    assertThat(accepted).isFalse();
    assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(attributes).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the handshake with 401 when the access token carries no expiry")
  void shouldRefuseHandshakeWith401WhenAccessTokenCarriesNoExpiry() {
    var unbounded =
        Jwt.withTokenValue(RAW_ACCESS_TOKEN).header("alg", "ES256").subject("account").build();
    SecurityContextHolder.setContext(
        new SecurityContextImpl(
            new StreamarrAuthenticationToken(
                AuthenticatedIdentityFixture.profileScopedBuilder().build(),
                unbounded,
                List.of())));

    var accepted =
        interceptor.beforeHandshake(request, response, new TextWebSocketHandler(), new HashMap<>());

    assertThat(accepted).isFalse();
    assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  @DisplayName("Should refuse the handshake with 401 when the credential is not an access token")
  void shouldRefuseHandshakeWith401WhenCredentialIsNotAccessToken() {
    SecurityContextHolder.setContext(
        new SecurityContextImpl(
            new StreamarrAuthenticationToken(
                AuthenticatedIdentityFixture.profileScopedBuilder().build(), null, List.of())));

    var accepted =
        interceptor.beforeHandshake(request, response, new TextWebSocketHandler(), new HashMap<>());

    assertThat(accepted).isFalse();
    assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  @DisplayName("Should leave the security context alone when a refused handshake ends")
  void shouldLeaveSecurityContextAloneWhenRefusedHandshakeEnds() {
    var current = SecurityContextHolder.getContext();
    interceptor.beforeHandshake(request, response, new TextWebSocketHandler(), new HashMap<>());

    interceptor.afterHandshake(request, response, new TextWebSocketHandler(), null);

    assertThat(SecurityContextHolder.getContext()).isSameAs(current);
  }

  private static StreamarrAuthenticationToken accessTokenAuthentication() {
    var jwt =
        Jwt.withTokenValue(RAW_ACCESS_TOKEN)
            .header("alg", "ES256")
            .subject("account")
            .issuedAt(ACCESS_EXPIRES_AT.minusSeconds(600))
            .expiresAt(ACCESS_EXPIRES_AT)
            .build();
    return new StreamarrAuthenticationToken(
        AuthenticatedIdentityFixture.profileScopedBuilder().build(),
        jwt,
        List.of(new SimpleGrantedAuthority("SCOPE_profile")));
  }
}
