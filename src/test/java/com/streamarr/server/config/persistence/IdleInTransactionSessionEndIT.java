package com.streamarr.server.config.persistence;

import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Idle In Transaction Session End Integration Tests")
@SpringBootTest(properties = "DATABASE_IDLE_IN_TRANSACTION_TIMEOUT=1s")
class IdleInTransactionSessionEndIT extends AbstractIntegrationTest {

  // PostgreSQL's SQLSTATE for a session ended by idle_in_transaction_session_timeout.
  private static final String IDLE_IN_TRANSACTION_SESSION_TIMEOUT = "25P03";
  private static final String LOCK_SETTINGS_ROW =
      "SELECT id FROM server_settings WHERE singleton FOR UPDATE";

  @Autowired private HikariDataSource pool;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("Should end the session and release its row lock when its transaction stays idle")
  void shouldEndTheSessionAndReleaseItsRowLockWhenItsTransactionStaysIdle() throws SQLException {
    try (var holder = pool.getConnection()) {
      lockSettingsRowUntilTheSessionEnds(holder);

      lockSettingsRowWithoutWaiting();
      assertEndedByIdleTimeout(holder);
    }
  }

  @Test
  @DisplayName("Should hand out a working connection when an ended connection returns to the pool")
  void shouldHandOutAWorkingConnectionWhenAnEndedConnectionReturnsToThePool() throws SQLException {
    // A pool of one connection, configured like the application's, hands the ended connection
    // back on the next borrow unless it replaced it.
    try (var singleConnectionPool = singleConnectionPoolLike(pool)) {
      int endedPid;
      try (var holder = singleConnectionPool.getConnection()) {
        endedPid = lockSettingsRowUntilTheSessionEnds(holder);
        assertEndedByIdleTimeout(holder);
      }

      try (var next = singleConnectionPool.getConnection()) {
        assertThat(backendPid(next)).isNotEqualTo(endedPid);
      }
    }
  }

  private static HikariDataSource singleConnectionPoolLike(HikariDataSource pool) {
    var config = new HikariConfig();
    config.setJdbcUrl(pool.getJdbcUrl());
    config.setUsername(pool.getUsername());
    config.setPassword(pool.getPassword());
    config.setDataSourceProperties(pool.getDataSourceProperties());
    config.setMaximumPoolSize(1);
    return new HikariDataSource(config);
  }

  private int lockSettingsRowUntilTheSessionEnds(Connection holder) throws SQLException {
    var holderPid = backendPid(holder);
    holder.setAutoCommit(false);
    lockSettingsRow(holder);

    await().atMost(Duration.ofSeconds(10)).until(() -> !isBackendConnected(holderPid));
    return holderPid;
  }

  private static void assertEndedByIdleTimeout(Connection holder) {
    assertThatThrownBy(() -> backendPid(holder))
        .isInstanceOfSatisfying(
            SQLException.class,
            failure ->
                assertThat(failure.getSQLState()).isEqualTo(IDLE_IN_TRANSACTION_SESSION_TIMEOUT));
  }

  private static void lockSettingsRow(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(LOCK_SETTINGS_ROW)) {
      assertThat(rows.next()).isTrue();
    }
  }

  // In autocommit, the statement takes the lock and releases it in its own transaction, so the
  // contender leaves no transaction for the idle bound to end.
  private void lockSettingsRowWithoutWaiting() throws SQLException {
    try (var contender = pool.getConnection();
        var statement = contender.createStatement();
        var rows = statement.executeQuery(LOCK_SETTINGS_ROW + " NOWAIT")) {
      assertThat(rows.next()).isTrue();
    }
  }

  private boolean isBackendConnected(int pid) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ?)", Boolean.class, pid));
  }
}
