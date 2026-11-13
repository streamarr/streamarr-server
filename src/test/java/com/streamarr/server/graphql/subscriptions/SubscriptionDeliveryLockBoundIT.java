package com.streamarr.server.graphql.subscriptions;

import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.PostgresLockTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Keeps the library locked until the server-wide lock bound ends a cancelled delivery's read. Only
 * this class shortens the bound, so no delivery test that must finish before it runs under it.
 */
@Tag("IntegrationTest")
@DisplayName("Subscription Delivery Lock Bound Integration Tests")
@TestPropertySource(properties = "DATABASE_LOCK_TIMEOUT=1s")
class SubscriptionDeliveryLockBoundIT extends AbstractSubscriptionDeliveryLockIntegrationTest {

  private static final Duration LOCK_BOUND = Duration.ofSeconds(1);
  private static final Duration SETTLE_MARGIN = Duration.ofSeconds(10);
  private static final String COUNTS_FIELD = "libraryCountsChanged";

  @Autowired private LibraryContentsBroadcasters contentsBroadcasters;

  @Test
  @DisplayName(
      "Should return the database connection of a cancelled delivery's read to the pool within the lock bound when the lock outlives the disconnect")
  void shouldReturnConnectionOfCancelledDeliverysReadWithinLockBoundWhenLockOutlivesDisconnect()
      throws SQLException {
    var socket = connect();
    socket.subscribe(
        "counts",
        "subscription { libraryCountsChanged(libraryId: \"%s\") { id } }"
            .formatted(library.getId()));
    awaitFirstCountsDelivery(socket);
    try (var held = lockTable("library");
        var observer = dataSource.getConnection()) {
      contentsBroadcasters.getCounts().broadcast(library.getId(), library.getId());
      var readerPid =
          PostgresLockTestSupport.awaitBlockedBackendPid(observer, held.backendPid(), null);
      assertThat(PostgresLockTestSupport.waitersBehind(observer, held.backendPid(), "%"))
          .as("only the delivery's read waits on the library lock")
          .isEqualTo(1);

      socket.close();
      activeSubscriptions.awaitCount(COUNTS_FIELD, 0);
      assertThat(PostgresLockTestSupport.blockingPids(observer, readerPid))
          .as("the operation ended while the delivery's read still waited on the library lock")
          .contains(held.backendPid());
      var settledState = awaitBackendIdleOrGone(observer, readerPid);

      assertThat(settledState)
          .as("the lock bound ends the cancelled read, and its pooled connection survives, idle")
          .contains("idle");
      assertThat(PostgresLockTestSupport.backendState(observer, readerPid))
          .as("the delivery's pooled connection is still open after it settled")
          .isPresent();
      assertThat(PostgresLockTestSupport.waitersBehind(observer, held.backendPid(), "%"))
          .as("nothing waits on the library lock that is still held")
          .isZero();
    }
  }

  private void awaitFirstCountsDelivery(GraphQlTransportWsTestClient socket) {
    activeSubscriptions.awaitCount(COUNTS_FIELD, 1);
    contentsBroadcasters.getCounts().broadcast(library.getId(), library.getId());
    // The registration's own library read runs before this delivery reaches the socket, so it
    // cannot wait on the lock taken next.
    assertThat(socket.nextMessageFor("counts").path("type").asString()).isEqualTo("next");
  }

  // A cancelled delivery sends the subscriber nothing, so only its backend shows that the read
  // ended: the backend is idle again, or gone with the connection. The state is returned from the
  // wait, because the pool may lend the connection out again before a second read.
  private static Optional<String> awaitBackendIdleOrGone(Connection observer, int backendPid) {
    return await()
        .atMost(LOCK_BOUND.plus(SETTLE_MARGIN))
        .until(
            () -> PostgresLockTestSupport.backendState(observer, backendPid),
            state -> state.filter(not("idle"::equals)).isEmpty());
  }
}
