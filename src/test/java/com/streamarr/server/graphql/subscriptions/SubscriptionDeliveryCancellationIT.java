package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
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
import com.streamarr.server.support.PostgresLockTestSupport.HeldRowLock;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.scheduler.Scheduler;

/**
 * Parks a subscription delivery's database read behind a lock held on another connection, so the
 * subscriber's operation can end, or the read can fail, while the read is in flight.
 */
@Tag("IntegrationTest")
@DisplayName("Subscription Delivery Cancellation Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubscriptionDeliveryCancellationIT extends AbstractIntegrationTest {

  private static final String COUNTS_FIELD = "libraryCountsChanged";
  private static final String STATUS_FIELD = "libraryStatusChanged";
  // administration checks the subscriber's live ServerAdmin authority on every delivery.
  private static final String STATUS_SUBSCRIPTION =
      "subscription { libraryStatusChanged { id administration { status } } }";
  private static final String FAIL_CLOSED_METRIC = "streamarr.authorization.fail_closed";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryAdministrationService libraryAdministration;
  @Autowired private LibraryBroadcasters libraryBroadcasters;
  @Autowired private LibraryContentsBroadcasters contentsBroadcasters;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
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
      "Should keep the database connection of a delivery's read when the subscriber disconnects while the read runs")
  void shouldKeepDatabaseConnectionOfDeliverysReadWhenSubscriberDisconnectsWhileReadRuns()
      throws SQLException {
    var socket = connect();
    socket.subscribe(
        "counts",
        "subscription { libraryCountsChanged(libraryId: \"%s\") { id } }"
            .formatted(library.getId()));
    activeSubscriptions.awaitCount(COUNTS_FIELD, 1);
    int readerPid;
    try (var held = lockLibraryTable();
        var observer = dataSource.getConnection()) {
      contentsBroadcasters.getCounts().broadcast(library.getId(), library.getId());
      readerPid = PostgresLockTestSupport.awaitBlockedBackendPid(observer, held.backendPid(), null);

      socket.close();
      activeSubscriptions.awaitCount(COUNTS_FIELD, 0);
    }

    awaitBackendIdleOrGone(readerPid);

    assertThat(backendState(readerPid)).contains("idle");
  }

  @Test
  @DisplayName(
      "Should count no authorization failure when a delivery's operation cancels while the delivery reads authority")
  void shouldCountNoAuthorizationFailureWhenDeliverysOperationCancelsWhileDeliveryReadsAuthority()
      throws SQLException {
    var identity = authTestSupport.identityOf(admin);
    var decided = new CountDownLatch(1);
    var failuresBefore = failClosedCount();
    var broadcaster = authorityCheckedBroadcaster();
    try (var held = lockAdminAccountRow();
        var observer = dataSource.getConnection()) {
      var operation =
          broadcaster
              .register()
              .doOnNext(_ -> requireViewLibraryAdministration(identity, decided))
              .subscribe();
      broadcaster.broadcast("SCANNING");
      PostgresLockTestSupport.awaitBlockedBackendPid(observer, held.backendPid(), null);

      operation.dispose();
    }

    PostgresLockTestSupport.awaitLatch(decided);
    assertThat(failClosedCount()).isEqualTo(failuresBefore);
  }

  @Test
  @DisplayName(
      "Should count an engine failure when a delivery's authority read fails while the operation stays open")
  void shouldCountEngineFailureWhenDeliverysAuthorityReadFailsWhileOperationStaysOpen()
      throws SQLException {
    var socket = connect();
    socket.subscribe("status", STATUS_SUBSCRIPTION);
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
    var failuresBefore = failClosedCount();
    try (var held = lockAdminAccountRow();
        var observer = dataSource.getConnection()) {
      libraryBroadcasters.getStatusChanges().broadcast(library);
      var readerPid =
          PostgresLockTestSupport.awaitBlockedBackendPid(observer, held.backendPid(), null);

      terminateBackend(observer, readerPid);
    }

    var delivery = socket.nextMessageFor("status").path("payload");

    assertThat(firstErrorCode(delivery)).isEqualTo("AUTHORIZATION_UNAVAILABLE");
    assertThat(failClosedCount()).isEqualTo(failuresBefore + 1);
  }

  private GraphQlTransportWsTestClient connect() {
    var socket =
        GraphQlTransportWsTestClient.connectWithBearer(port, authTestSupport.profileBearer(admin));
    sockets.add(socket);
    return socket;
  }

  // A delivery that checks live authority on the delivery thread, like a subscription that ends
  // an operation whose subscriber has lost the authority to receive it.
  private SubscriptionBroadcaster<String> authorityCheckedBroadcaster() {
    return SubscriptionBroadcaster.<String>builder()
        .field(STATUS_FIELD)
        .meterRegistry(new SimpleMeterRegistry())
        .deliveryScheduler(graphQlSubscriptionDeliveryScheduler)
        .operationBuffer(1)
        .build();
  }

  private void requireViewLibraryAdministration(
      AuthenticatedIdentity identity, CountDownLatch decided) {
    try {
      libraryAdministration.requireViewLibraryAdministration(identity);
    } finally {
      decided.countDown();
    }
  }

  private HeldRowLock lockAdminAccountRow() throws SQLException {
    return PostgresLockTestSupport.lockRow(
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("user_account")
            .rowId(admin.account().getId())
            .build());
  }

  // findById reads no row lock, so only a table lock parks it.
  private HeldTableLock lockLibraryTable() throws SQLException {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute("LOCK TABLE library IN ACCESS EXCLUSIVE MODE");
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }

    return new HeldTableLock(connection, PostgresLockTestSupport.backendPid(connection));
  }

  private double failClosedCount() {
    return meterRegistry.find(FAIL_CLOSED_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  // A cancelled delivery sends the subscriber nothing, so only its backend shows that the read
  // ended: the backend is idle again, or gone with the connection.
  private void awaitBackendIdleOrGone(int backendPid) {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> backendState(backendPid).filter(state -> !"idle".equals(state)).isEmpty());
  }

  private Optional<String> backendState(int backendPid) {
    return jdbcTemplate
        .queryForList("SELECT state FROM pg_stat_activity WHERE pid = ?", String.class, backendPid)
        .stream()
        .findFirst();
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
