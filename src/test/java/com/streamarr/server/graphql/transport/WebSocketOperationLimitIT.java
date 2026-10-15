package com.streamarr.server.graphql.transport;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
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
  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id status } }";

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
  @DisplayName("Should refuse an operation with TOO_MANY_OPERATIONS when the connection is full")
  void shouldRefuseOperationWithTooManyOperationsWhenConnectionIsFull() {
    try (var socket = connect()) {
      subscribeUpToLimit(socket);

      socket.subscribe("one-too-many", STATUS_SUBSCRIPTION);

      var refusal = socket.nextMessageFor("one-too-many");
      assertThat(refusal.path("type").asString()).isEqualTo("next");
      assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo("TOO_MANY_OPERATIONS");
      assertThat(socket.nextMessageFor("one-too-many").path("type").asString())
          .isEqualTo("complete");
      assertThat(activeSubscriptions.count(STATUS_FIELD)).isEqualTo(MAX_OPERATIONS_PER_CONNECTION);
    }

    activeSubscriptions.awaitCount(STATUS_FIELD, 0);
  }

  @Test
  @DisplayName("Should accept a new operation when the client completes one on a full connection")
  void shouldAcceptNewOperationWhenClientCompletesOneOnFullConnection() {
    try (var socket = connect()) {
      subscribeUpToLimit(socket);

      socket.complete("status-0");
      activeSubscriptions.awaitCount(STATUS_FIELD, MAX_OPERATIONS_PER_CONNECTION - 1);
      socket.subscribe("replacement", STATUS_SUBSCRIPTION);

      awaitActiveOperations(MAX_OPERATIONS_PER_CONNECTION);
    }
  }

  @Test
  @DisplayName("Should release every operation when the client disconnects")
  void shouldReleaseEveryOperationWhenClientDisconnects() {
    try (var socket = connect()) {
      socket.subscribe("first", STATUS_SUBSCRIPTION);
      socket.subscribe("second", STATUS_SUBSCRIPTION);
      activeSubscriptions.awaitCount(STATUS_FIELD, 2);
    }

    awaitActiveOperations(0);
  }

  @Test
  @DisplayName("Should accept an operation when another connection is full")
  void shouldAcceptOperationWhenAnotherConnectionIsFull() {
    try (var full = connect();
        var fresh = connect()) {
      subscribeUpToLimit(full);

      fresh.subscribe("status", STATUS_SUBSCRIPTION);

      awaitActiveOperations(MAX_OPERATIONS_PER_CONNECTION + 1);
    }
  }

  private void awaitActiveOperations(int expected) {
    await()
        .untilAsserted(
            () -> assertThat(activeSubscriptions.count(STATUS_FIELD)).isEqualTo(expected));
  }

  private void subscribeUpToLimit(GraphQlTransportWsTestClient socket) {
    for (var operation = 0; operation < MAX_OPERATIONS_PER_CONNECTION; operation++) {
      socket.subscribe("status-" + operation, STATUS_SUBSCRIPTION);
    }

    activeSubscriptions.awaitCount(STATUS_FIELD, MAX_OPERATIONS_PER_CONNECTION);
  }

  private GraphQlTransportWsTestClient connect() {
    return GraphQlTransportWsTestClient.connectWithBearer(
        port, authTestSupport.profileBearer(admin));
  }
}
