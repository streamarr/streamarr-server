package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthCookies;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient.Handshake;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The opening HTTP handshake authenticates with the access cookie (browsers) or a bearer header
 * (native clients). Browsers must also send an allowed Origin: the server's own origin or the
 * configured STREAMARR_BASE_URL origin. Native clients send no Origin.
 */
@Tag("IntegrationTest")
@DisplayName("GraphQL WebSocket Transport Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphQlWebSocketTransportIT extends AbstractIntegrationTest {

  // The test profile's STREAMARR_BASE_URL.
  private static final String CONFIGURED_BASE_URL_ORIGIN = "https://home.example.test";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;

  private AuthTestSupport.TestIdentity identity;

  @BeforeEach
  void createIdentity() {
    identity = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void deleteIdentity() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should refuse the handshake with 401 when no credential is presented")
  void shouldRefuseHandshakeWith401WhenNoCredentialIsPresented() {
    assertThat(GraphQlTransportWsTestClient.refusedHandshakeStatus(handshake().build()))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("Should refuse the handshake with 401 when the access token has expired")
  void shouldRefuseHandshakeWith401WhenAccessTokenExpired() {
    var expired =
        handshake().bearerToken(Optional.of(authTestSupport.expiredProfileBearer(identity)));

    assertThat(GraphQlTransportWsTestClient.refusedHandshakeStatus(expired.build())).isEqualTo(401);
  }

  @Test
  @DisplayName("Should refuse the handshake with 403 when the Origin is another site")
  void shouldRefuseHandshakeWith403WhenOriginIsCrossSite() {
    var crossSite =
        handshake()
            .cookie(Optional.of(accessCookie()))
            .origin(Optional.of("https://attacker.example"));

    assertThat(GraphQlTransportWsTestClient.refusedHandshakeStatus(crossSite.build()))
        .isEqualTo(403);
  }

  @Test
  @DisplayName("Should accept the handshake when the access cookie and the same origin are sent")
  void shouldAcceptHandshakeWhenAccessCookieAndSameOriginArePresented() {
    var sameOrigin =
        handshake()
            .cookie(Optional.of(accessCookie()))
            .origin(Optional.of("http://localhost:%d".formatted(port)));

    try (var socket = GraphQlTransportWsTestClient.connect(sameOrigin.build())) {
      assertThat(socket.initialize()).isNotNull();
    }
  }

  @Test
  @DisplayName(
      "Should accept the handshake when the Origin is the configured base URL behind a proxy")
  void shouldAcceptHandshakeWhenOriginIsConfiguredBaseUrl() {
    var proxied =
        handshake()
            .cookie(Optional.of(accessCookie()))
            .origin(Optional.of(CONFIGURED_BASE_URL_ORIGIN));

    try (var socket = GraphQlTransportWsTestClient.connect(proxied.build())) {
      assertThat(socket.initialize()).isNotNull();
    }
  }

  @Test
  @DisplayName("Should accept the handshake when a native client sends a bearer and no Origin")
  void shouldAcceptHandshakeWhenNativeClientSendsBearerWithoutOrigin() {
    var nativeClient =
        handshake().bearerToken(Optional.of(authTestSupport.profileBearer(identity)));

    try (var socket = GraphQlTransportWsTestClient.connect(nativeClient.build())) {
      assertThat(socket.initialize()).isNotNull();
    }
  }

  private Handshake.HandshakeBuilder handshake() {
    return Handshake.builder().endpoint(URI.create("ws://localhost:%d/graphql".formatted(port)));
  }

  private String accessCookie() {
    return AuthCookies.ACCESS_COOKIE + "=" + authTestSupport.profileBearer(identity);
  }
}
