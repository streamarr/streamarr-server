package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
  private ActiveSubscriptions activeSubscriptions;

  @BeforeEach
  void createAdmin() {
    admin = authTestSupport.createAdminIdentity();
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
  }

  @AfterEach
  void deleteAdmin() {
    activeSubscriptions.awaitNoneActive();
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should close with 4100 and no complete frame when the opening access token expires")
  void shouldCloseWith4100AndNoCompleteFrameWhenOpeningAccessTokenExpires() {
    var expiresAt = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
    var bearer = authTestSupport.profileBearerExpiringAt(admin, expiresAt);

    try (var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer)) {
      socket.subscribe("status", "subscription { libraryStatusChanged { id status } }");
      activeSubscriptions.awaitCount(STATUS_FIELD, 1);

      var closure = socket.awaitClosure();

      assertThat(closure.statusCode()).isEqualTo(4100);
      assertThat(closure.reason()).isEqualTo("Access token expired");
      assertThat(Instant.now()).isAfterOrEqualTo(expiresAt);
      assertThat(socket.receivedMessages())
          .noneMatch(message -> "complete".equals(message.path("type").asString()));
      activeSubscriptions.awaitCount(STATUS_FIELD, 0);
    }
  }
}
