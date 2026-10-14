package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * A socket lives no longer than the access token that opened it. At expiry the server closes it
 * with the private code 4100 and sends no {@code complete} first, so clients restore their
 * operations on a new socket with a fresh token.
 */
@Tag("IntegrationTest")
@DisplayName("WebSocket Access Expiry Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketAccessExpiryIT extends AbstractIntegrationTest {

  private static final String STATUS_FIELD = "libraryStatusChanged";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MeterRegistry meterRegistry;

  private AuthTestSupport.TestIdentity admin;

  @BeforeEach
  void createAdmin() {
    admin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void deleteAdmin() {
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should close with 4100 and no complete frame when the opening access token expires")
  void shouldCloseWith4100AndNoCompleteFrameWhenOpeningAccessTokenExpires() {
    var expiresAt = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
    var bearer = authTestSupport.profileBearerExpiringAt(admin, expiresAt);

    try (var socket =
        GraphQlTransportWsTestClient.connect(
            GraphQlTransportWsTestClient.Handshake.builder()
                .endpoint(URI.create("ws://localhost:%d/graphql".formatted(port)))
                .bearerToken(Optional.of(bearer))
                .build())) {
      socket.initialize();
      socket.subscribe("status", "subscription { libraryStatusChanged { id status } }");
      awaitActiveStatusSubscriptions(1);

      var closure = socket.awaitClosure();

      assertThat(closure.statusCode()).isEqualTo(4100);
      assertThat(closure.reason()).isEqualTo("Access token expired");
      assertThat(Instant.now()).isAfterOrEqualTo(expiresAt);
      assertThat(socket.receivedMessages())
          .noneMatch(message -> "complete".equals(message.path("type").asString()));
      awaitActiveStatusSubscriptions(0);
    }
  }

  // Registration and release have no protocol message (ADR 0042), so the test waits on the gauge.
  private void awaitActiveStatusSubscriptions(int expected) {
    await().atMost(Duration.ofSeconds(10)).until(() -> activeStatusSubscriptions() == expected);
  }

  private double activeStatusSubscriptions() {
    var gauge =
        meterRegistry
            .find("streamarr.graphql.subscriptions.active")
            .tag("field", STATUS_FIELD)
            .gauge();
    if (gauge == null) {
      return 0;
    }

    return gauge.value();
  }
}
