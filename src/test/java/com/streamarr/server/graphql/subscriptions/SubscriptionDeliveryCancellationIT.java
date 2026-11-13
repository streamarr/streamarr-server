package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.PostgresLockTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import reactor.core.scheduler.Scheduler;

/**
 * Parks a subscription delivery's database read behind a lock held on another connection, so the
 * subscriber's operation can end, or the read can fail, while the read is in flight. A short lock
 * bound lets a test keep the lock until the bound ends the read.
 */
@Tag("IntegrationTest")
@DisplayName("Subscription Delivery Cancellation Integration Tests")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "DATABASE_LOCK_TIMEOUT=1s")
class SubscriptionDeliveryCancellationIT extends AbstractIntegrationTest {

  private static final Duration LOCK_BOUND = Duration.ofSeconds(1);
  private static final Duration SETTLE_MARGIN = Duration.ofSeconds(10);

  private static final String COUNTS_FIELD = "libraryCountsChanged";
  private static final String STATUS_FIELD = "libraryStatusChanged";
  // administration checks the subscriber's live ServerAdmin authority on every delivery.
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id administration { status } } }";
  private static final String FAIL_CLOSED_METRIC = "streamarr.authorization.fail_closed";
  private static final String ALLOWED = "allowed";
  // The jOOQ read of the Account's live authority; other backends may also wait on user_account.
  private static final String AUTHORITY_READ = "%\"user_account\".\"server_admin\"%";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryAdministrationService libraryAdministration;
  @Autowired private LibraryBroadcasters libraryBroadcasters;
  @Autowired private LibraryContentsBroadcasters contentsBroadcasters;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private DataSource dataSource;
  @Autowired private Scheduler graphQlSubscriptionDeliveryScheduler;

  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private AuthTestSupport.TestIdentity admin;
  private Library library;

  @BeforeEach
  void createServerAdminAndLibrary() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    admin = authTestSupport.createAdminIdentity();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    activeSubscriptions.awaitNoneActive();
    libraryRepository.deleteById(library.getId());
    authTestSupport.deleteIdentity(admin);
  }

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
    try (var held = lockLibraryTable();
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

  private GraphQlTransportWsTestClient connect() {
    var socket =
        GraphQlTransportWsTestClient.connectWithBearer(port, authTestSupport.profileBearer(admin));
    sockets.add(socket);
    return socket;
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

  private void awaitFirstCountsDelivery(GraphQlTransportWsTestClient socket) {
    activeSubscriptions.awaitCount(COUNTS_FIELD, 1);
    contentsBroadcasters.getCounts().broadcast(library.getId(), library.getId());
    // The registration's own library read runs before this delivery reaches the socket, so it
    // cannot wait on the lock taken next.
    assertThat(socket.nextMessageFor("counts").path("type").asString()).isEqualTo("next");
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

  private HeldTableLock lockLibraryTable() throws SQLException {
    return lockTable("library");
  }

  // Neither the library's findById nor a read decision's authority read takes a row lock, so only
  // a table lock parks them.
  private HeldTableLock lockTable(String table) throws SQLException {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }

    return new HeldTableLock(connection, PostgresLockTestSupport.backendPid(connection));
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

  private static void terminateBackend(Connection observer, int backendPid) throws SQLException {
    try (var statement = observer.prepareStatement("SELECT pg_terminate_backend(?)")) {
      statement.setInt(1, backendPid);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getBoolean(1)).isTrue();
      }
    }
  }

  private record HeldTableLock(Connection connection, int backendPid) implements AutoCloseable {

    @Override
    public void close() throws SQLException {
      try {
        connection.rollback();
      } finally {
        connection.close();
      }
    }
  }
}
