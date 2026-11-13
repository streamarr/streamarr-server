package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.support.PostgresLockTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.scheduler.Scheduler;

/**
 * Each test ends its delivery's lock wait itself, so the class runs under the default lock bound. A
 * test that waits for the bound to end the read belongs in {@link SubscriptionDeliveryLockBoundIT}.
 */
@Tag("IntegrationTest")
@DisplayName("Subscription Delivery Cancellation Integration Tests")
class SubscriptionDeliveryCancellationIT extends AbstractSubscriptionDeliveryLockIntegrationTest {

  private static final Duration SETTLE_MARGIN = Duration.ofSeconds(10);

  private static final String STATUS_FIELD = "libraryStatusChanged";
  // administration checks the subscriber's live ServerAdmin authority on every delivery.
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id administration { status } } }";
  private static final String FAIL_CLOSED_METRIC = "streamarr.authorization.fail_closed";
  private static final String ALLOWED = "allowed";
  // The jOOQ read of the Account's live authority; other backends may also wait on user_account.
  private static final String AUTHORITY_READ = "%\"user_account\".\"server_admin\"%";

  @Autowired private LibraryAdministrationService libraryAdministration;
  @Autowired private LibraryBroadcasters libraryBroadcasters;
  @Autowired private Scheduler graphQlSubscriptionDeliveryScheduler;

  @Test
  @DisplayName(
      "Should count no authorization failure when a delivery's operation cancels while the delivery reads authority")
  void shouldCountNoAuthorizationFailureWhenDeliverysOperationCancelsWhileDeliveryReadsAuthority()
      throws SQLException {
    var identity = authTestSupport.identityOf(admin);
    var decision = new CompletableFuture<String>();
    var failuresBefore = failClosedCount();
    var broadcaster = authorityCheckedBroadcaster();
    try (var held = lockUserAccountTable();
        var observer = dataSource.getConnection()) {
      var operation =
          broadcaster
              .register()
              .doOnNext(_ -> decision.complete(viewLibraryAdministrationDecision(identity)))
              .subscribe();
      broadcaster.broadcast("SCANNING");
      awaitBlockedAuthorityRead(observer, held);

      operation.dispose();
    }

    assertThat(decision)
        .as("the authority read finished with a real answer")
        .succeedsWithin(Duration.ofSeconds(10))
        .isEqualTo(ALLOWED);
    assertThat(failClosedCount()).isEqualTo(failuresBefore);
  }

  @Test
  @DisplayName(
      "Should fail closed and count an engine failure when a cancelled delivery's authority read fails")
  void shouldFailClosedAndCountEngineFailureWhenCancelledDeliverysAuthorityReadFails()
      throws SQLException {
    var identity = authTestSupport.identityOf(admin);
    var decision = new CompletableFuture<String>();
    var engineFailuresBefore = engineFailureCount();
    var broadcaster = authorityCheckedBroadcaster();
    try (var held = lockUserAccountTable();
        var observer = dataSource.getConnection()) {
      var operation =
          broadcaster
              .register()
              .doOnNext(_ -> decision.complete(viewLibraryAdministrationDecision(identity)))
              .subscribe();
      broadcaster.broadcast("SCANNING");
      var readerPid = awaitBlockedAuthorityRead(observer, held);
      operation.dispose();
      PostgresLockTestSupport.cancelStatement(observer, readerPid);

      assertThat(decision)
          .as("the authority read fails while user_account stays locked")
          .succeedsWithin(SETTLE_MARGIN)
          .isEqualTo("AuthorizationUnavailableException");
    }

    assertThat(engineFailureCount()).isEqualTo(engineFailuresBefore + 1);
  }

  @Test
  @DisplayName(
      "Should deliver AUTHORIZATION_UNAVAILABLE and count an engine failure when a delivery's authority read fails while the operation stays open")
  void
      shouldDeliverAuthorizationUnavailableAndCountEngineFailureWhenDeliverysAuthorityReadFailsWhileOperationStaysOpen()
          throws SQLException {
    var socket = connect();
    socket.subscribe("status", STATUS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    var engineFailuresBefore = engineFailureCount();
    try (var held = lockUserAccountTable();
        var observer = dataSource.getConnection()) {
      libraryBroadcasters.getStatusChanges().broadcast(library);
      var readerPid = awaitBlockedAuthorityRead(observer, held);

      terminateBackend(observer, readerPid);
    }

    var delivery = socket.nextMessageFor("status").path("payload");

    assertThat(firstErrorCode(delivery)).isEqualTo("AUTHORIZATION_UNAVAILABLE");
    assertThat(engineFailureCount()).isEqualTo(engineFailuresBefore + 1);
  }

  // A delivery that reads live authority on the delivery thread, as the administration field does
  // for every libraryStatusChanged value.
  private SubscriptionBroadcaster<String> authorityCheckedBroadcaster() {
    return SubscriptionBroadcaster.<String>builder()
        .field(STATUS_FIELD)
        .meterRegistry(new SimpleMeterRegistry())
        .deliveryScheduler(graphQlSubscriptionDeliveryScheduler)
        .operationBuffer(1)
        .build();
  }

  private String viewLibraryAdministrationDecision(AuthenticatedIdentity identity) {
    try {
      libraryAdministration.requireViewLibraryAdministration(identity);
      return ALLOWED;
    } catch (RuntimeException refusal) {
      return refusal.getClass().getSimpleName();
    }
  }

  private HeldTableLock lockUserAccountTable() throws SQLException {
    return lockTable("user_account");
  }

  private static int awaitBlockedAuthorityRead(Connection observer, HeldTableLock held) {
    return PostgresLockTestSupport.awaitBlockedBackendPidRunning(
        observer, held.backendPid(), AUTHORITY_READ);
  }

  private double engineFailureCount() {
    var counter = meterRegistry.find(FAIL_CLOSED_METRIC).tag("cause", "ENGINE_FAILURE").counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }

  private double failClosedCount() {
    return meterRegistry.find(FAIL_CLOSED_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private static void terminateBackend(Connection observer, int backendPid) throws SQLException {
    try (var statement = observer.prepareStatement("SELECT pg_terminate_backend(?)")) {
      statement.setInt(1, backendPid);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getBoolean(1)).isTrue();
      }
    }
  }
}
