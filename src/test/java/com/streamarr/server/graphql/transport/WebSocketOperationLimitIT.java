package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
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
 * One WebSocket runs at most the configured number of operations at once (32 by default). An
 * operation releases its place when the client completes it or the connection closes.
 */
@Tag("IntegrationTest")
@DisplayName("WebSocket Operation Limit Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketOperationLimitIT extends AbstractIntegrationTest {

  private static final int MAX_OPERATIONS_PER_CONNECTION = 32;
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id status } }";

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
  @DisplayName("Should refuse an operation with TOO_MANY_OPERATIONS when the connection is full")
  void shouldRefuseOperationWithTooManyOperationsWhenConnectionIsFull() {
    try (var socket = connect()) {
      subscribeUpToLimit(socket);

      socket.subscribe("one-too-many", STATUS_SUBSCRIPTION);

      var refusal = socket.nextMessageFor("one-too-many");
      assertThat(refusal.path("type").asString()).isEqualTo("next");
      assertThat(
              refusal
                  .path("payload")
                  .path("errors")
                  .path(0)
                  .path("extensions")
                  .path("code")
                  .asString())
          .isEqualTo("TOO_MANY_OPERATIONS");
      assertThat(socket.nextMessageFor("one-too-many").path("type").asString())
          .isEqualTo("complete");
      assertThat(activeStatusSubscriptions()).isEqualTo(MAX_OPERATIONS_PER_CONNECTION);
    }

    awaitActiveStatusSubscriptions(0);
  }

  @Test
  @DisplayName("Should accept a new operation when the client completes one on a full connection")
  void shouldAcceptNewOperationWhenClientCompletesOneOnFullConnection() {
    try (var socket = connect()) {
      subscribeUpToLimit(socket);

      socket.complete("status-0");
      awaitActiveStatusSubscriptions(MAX_OPERATIONS_PER_CONNECTION - 1);
      socket.subscribe("replacement", STATUS_SUBSCRIPTION);

      awaitActiveStatusSubscriptions(MAX_OPERATIONS_PER_CONNECTION);
    }
  }

  @Test
  @DisplayName("Should release every operation when the client disconnects")
  void shouldReleaseEveryOperationWhenClientDisconnects() {
    try (var socket = connect()) {
      socket.subscribe("first", STATUS_SUBSCRIPTION);
      socket.subscribe("second", STATUS_SUBSCRIPTION);
      awaitActiveStatusSubscriptions(2);
    }

    awaitActiveStatusSubscriptions(0);
  }

  @Test
  @DisplayName("Should give a new connection its own operation limit")
  void shouldGiveNewConnectionItsOwnOperationLimit() {
    try (var full = connect();
        var fresh = connect()) {
      subscribeUpToLimit(full);

      fresh.subscribe("status", STATUS_SUBSCRIPTION);

      awaitActiveStatusSubscriptions(MAX_OPERATIONS_PER_CONNECTION + 1);
    }
  }

  private void subscribeUpToLimit(GraphQlTransportWsTestClient socket) {
    for (var operation = 0; operation < MAX_OPERATIONS_PER_CONNECTION; operation++) {
      socket.subscribe("status-" + operation, STATUS_SUBSCRIPTION);
    }

    awaitActiveStatusSubscriptions(MAX_OPERATIONS_PER_CONNECTION);
  }

  private GraphQlTransportWsTestClient connect() {
    return GraphQlTransportWsTestClient.connect(
            GraphQlTransportWsTestClient.Handshake.builder()
                .endpoint(URI.create("ws://localhost:%d/graphql".formatted(port)))
                .bearerToken(Optional.of(authTestSupport.profileBearer(admin)))
                .build())
        .initialize();
  }

  // Registration and release have no protocol message (ADR 0042), so the test waits on the gauge.
  private void awaitActiveStatusSubscriptions(int expected) {
    await().atMost(Duration.ofSeconds(10)).until(() -> activeStatusSubscriptions() == expected);
  }

  private double activeStatusSubscriptions() {
    var gauge =
        meterRegistry
            .find("streamarr.graphql.subscriptions.active")
            .tag("field", "libraryStatusChanged")
            .gauge();
    if (gauge == null) {
      return 0;
    }

    return gauge.value();
  }
}
