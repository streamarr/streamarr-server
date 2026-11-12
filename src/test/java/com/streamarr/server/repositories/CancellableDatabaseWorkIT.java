package com.streamarr.server.repositories;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.HeldCancelRequests;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Cancellable Database Work Integration Tests")
class CancellableDatabaseWorkIT extends AbstractIntegrationTest {

  // A wait that no cancel ends lasts the statement's 30 s of pg_sleep, or the pool's connection
  // timeout.
  private static final Duration BOUND = Duration.ofSeconds(10);
  // The driver sends a cancel request over a connection of its own and waits for PostgreSQL to
  // close it, for up to its cancelSignalTimeout of 10 s. A cancel that waited for a held request
  // would outlast this bound.
  private static final Duration CANCEL_BOUND = Duration.ofSeconds(5);
  private static final String LONG_STATEMENT = "SELECT pg_sleep(30)";

  @Autowired private DSLContext dsl;
  @Autowired private HikariDataSource pool;
  @Autowired private JdbcTemplate jdbc;

  private final CancellableDatabaseWork work = new CancellableDatabaseWork();
  // One connection, so a connection that the pool discarded shows as a new backend.
  private HikariDataSource singleConnectionPool;

  @BeforeEach
  void openSingleConnectionPool() {
    singleConnectionPool = singleConnectionPoolAt(pool.getJdbcUrl());
  }

  @AfterEach
  void closeSingleConnectionPool() {
    singleConnectionPool.close();
  }

  @Test
  @DisplayName(
      "Should end the running statement and keep its connection when the work is cancelled")
  void shouldEndTheRunningStatementAndKeepItsConnectionWhenTheWorkIsCancelled() throws Exception {
    var connectionBefore = singleConnectionsBackend();
    var context = contextOver(singleConnectionPool);
    Optional<Integer> result;
    int cancelledCalls;

    try (var running = BoundedTask.start(() -> work.call(() -> context.execute(LONG_STATEMENT)))) {
      awaitSleeping(connectionBefore);
      cancelledCalls = work.cancel();
      result = running.await(BOUND);
    }

    assertThat(result).isEmpty();
    assertThat(cancelledCalls).isOne();
    assertThat(singleConnectionsBackend()).isEqualTo(connectionBefore);
  }

  @Test
  @DisplayName(
      "Should return from the cancel before PostgreSQL gets the cancel request when the network"
          + " holds the request")
  void shouldReturnFromTheCancelBeforePostgresGetsTheCancelRequestWhenTheNetworkHoldsIt()
      throws Exception {
    Optional<Integer> result;
    int cancelledCalls;

    try (var network = HeldCancelRequests.inFrontOf(pool.getJdbcUrl());
        var proxiedPool = singleConnectionPoolAt(network.jdbcUrl())) {
      var connection = backendOf(proxiedPool);
      var context = contextOver(proxiedPool);
      try (var running =
          BoundedTask.start(() -> work.call(() -> context.execute(LONG_STATEMENT)))) {
        awaitSleeping(connection);
        try (var cancelling = BoundedTask.start(work::cancel)) {
          cancelledCalls = cancelling.await(CANCEL_BOUND);
        }

        network.awaitHeldCancelRequest();
        network.release();
        result = running.await(BOUND);
      }
    }

    assertThat(cancelledCalls).isOne();
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should end the wait for a pooled connection when the work is cancelled")
  void shouldEndTheWaitForAPooledConnectionWhenTheWorkIsCancelled() throws Exception {
    var context = contextOver(singleConnectionPool);
    Optional<Integer> result;
    int cancelledCalls;

    try (var _ = singleConnectionPool.getConnection();
        var waiting = BoundedTask.start(() -> work.call(() -> context.execute("SELECT 1")))) {
      await()
          .atMost(BOUND)
          .until(
              () -> singleConnectionPool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1);
      cancelledCalls = work.cancel();
      result = waiting.await(BOUND);
    }

    assertThat(result).isEmpty();
    assertThat(cancelledCalls).isOne();
  }

  // The cancel finds no running statement and interrupts the work, and the transaction still
  // holds its connection: the next statement and the rollback must not read the socket with the
  // interrupt pending.
  @Test
  @DisplayName(
      "Should refuse the next statement and keep the connection when the work is cancelled between"
          + " two statements of a transaction")
  void shouldRefuseTheNextStatementAndKeepTheConnectionWhenCancelledBetweenTwoStatements()
      throws Exception {
    var connectionBefore = singleConnectionsBackend();
    var context = contextOver(new TransactionAwareDataSourceProxy(singleConnectionPool));
    var transactions =
        new TransactionTemplate(new DataSourceTransactionManager(singleConnectionPool));
    var betweenStatements = new CountDownLatch(1);
    var resume = new AtomicBoolean();
    var secondStatementRan = new AtomicBoolean();
    Optional<Integer> result;

    try (var running =
        BoundedTask.start(
            () ->
                work.call(
                    () ->
                        transactions.execute(
                            _ -> {
                              context.execute("SELECT 1");
                              betweenStatements.countDown();
                              // Spins rather than waits, so the interrupt stays pending.
                              while (!resume.get()) {
                                Thread.onSpinWait();
                              }

                              context.execute("SELECT 2");
                              secondStatementRan.set(true);
                              return 2;
                            })))) {
      awaitLatch(betweenStatements);
      work.cancel();
      resume.set(true);
      result = running.await(BOUND);
    }

    assertThat(result).isEmpty();
    assertThat(secondStatementRan).isFalse();
    assertThat(singleConnectionsBackend()).isEqualTo(connectionBefore);
  }

  @Test
  @DisplayName(
      "Should report the failure when PostgreSQL cancels the statement although the work was not"
          + " cancelled")
  void shouldReportTheFailureWhenPostgresCancelsTheStatementAlthoughTheWorkWasNotCancelled()
      throws Exception {
    var connection = singleConnectionsBackend();
    var context = contextOver(singleConnectionPool);

    try (var running = BoundedTask.start(() -> work.call(() -> context.execute(LONG_STATEMENT)))) {
      awaitSleeping(connection);
      jdbc.queryForObject("SELECT pg_cancel_backend(?)", Boolean.class, connection);

      assertThatThrownBy(() -> running.await(BOUND)).isInstanceOf(DataAccessException.class);
    }
  }

  private DSLContext contextOver(DataSource dataSource) {
    return dsl.configuration().derive(dataSource).dsl();
  }

  private HikariDataSource singleConnectionPoolAt(String jdbcUrl) {
    var config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(pool.getUsername());
    config.setPassword(pool.getPassword());
    config.setDataSourceProperties(pool.getDataSourceProperties());
    config.setMaximumPoolSize(1);
    return new HikariDataSource(config);
  }

  private int singleConnectionsBackend() throws SQLException {
    return backendOf(singleConnectionPool);
  }

  private static int backendOf(DataSource dataSource) throws SQLException {
    try (var connection = dataSource.getConnection()) {
      return backendPid(connection);
    }
  }

  private void awaitSleeping(int backend) {
    await()
        .atMost(BOUND)
        .until(
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event = 'PgSleep'",
                    Integer.class,
                    backend),
            sleeping -> sleeping == 1);
  }
}
