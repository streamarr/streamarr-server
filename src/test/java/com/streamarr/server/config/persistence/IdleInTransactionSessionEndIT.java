package com.streamarr.server.config.persistence;

import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Idle In Transaction Session End Integration Tests")
@SpringBootTest(properties = "DATABASE_IDLE_IN_TRANSACTION_TIMEOUT=2s")
class IdleInTransactionSessionEndIT extends AbstractIntegrationTest {

  // PostgreSQL's SQLSTATE for a session ended by idle_in_transaction_session_timeout.
  private static final String IDLE_IN_TRANSACTION_SESSION_TIMEOUT = "25P03";
  private static final String LOCK_SETTINGS_ROW =
      "SELECT id FROM server_settings WHERE singleton FOR UPDATE";

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("Should end the session and release its row lock when its transaction stays idle")
  void shouldEndTheSessionAndReleaseItsRowLockWhenItsTransactionStaysIdle() throws SQLException {
    try (var holder = dataSource.getConnection()) {
      lockSettingsRowUntilTheSessionEnds(holder);

      lockSettingsRowWithoutWaiting();
      assertEndedByIdleTimeout(holder);
    }
  }

  @Test
  @DisplayName("Should hand out a working connection when an ended connection returns to the pool")
  void shouldHandOutAWorkingConnectionWhenAnEndedConnectionReturnsToThePool() throws SQLException {
    try (var holder = dataSource.getConnection()) {
      lockSettingsRowUntilTheSessionEnds(holder);
      assertEndedByIdleTimeout(holder);
    }

    // The pool hands this thread's most recently returned connection out first, so a pool that
    // kept the ended connection would return it here.
    try (var next = dataSource.getConnection();
        var statement = next.createStatement();
        var result = statement.executeQuery("SELECT 1")) {
      assertThat(result.next()).isTrue();
    }
  }

  private void lockSettingsRowUntilTheSessionEnds(Connection holder) throws SQLException {
    holder.setAutoCommit(false);
    lockSettingsRow(holder);
    var holderPid = backendPid(holder);

    await().atMost(Duration.ofSeconds(10)).until(() -> !isBackendConnected(holderPid));
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

  private void lockSettingsRowWithoutWaiting() throws SQLException {
    try (var contender = dataSource.getConnection()) {
      contender.setAutoCommit(false);
      try (var statement = contender.createStatement();
          var rows = statement.executeQuery(LOCK_SETTINGS_ROW + " NOWAIT")) {
        assertThat(rows.next()).isTrue();
      } finally {
        contender.rollback();
      }
    }
  }

  private boolean isBackendConnected(int pid) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ?)", Boolean.class, pid));
  }
}
