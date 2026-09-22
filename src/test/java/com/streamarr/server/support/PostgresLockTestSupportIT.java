package com.streamarr.server.support;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

@Tag("IntegrationTest")
@DisplayName("PostgreSQL Lock Test Support Integration Tests")
class PostgresLockTestSupportIT extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcConnectionDetails connectionDetails;

  @Test
  @DisplayName(
      "Should find a blocked backend when it connects after the observer first reads activity")
  void shouldFindBlockedBackendWhenItConnectsAfterTheObserverFirstReadsActivity() throws Exception {
    var lockKey = ThreadLocalRandom.current().nextLong();
    try (var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lockAdvisoryKey(holder, lockKey);
      var holderPid = backendPid(holder);
      readBackendActivity(holder);

      try (var contender = openUnpooledConnection();
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var contenderPid = backendPid(contender);
        var contention =
            executor.submit(
                () -> {
                  lockAdvisoryKey(contender, lockKey);
                  return null;
                });

        try {
          assertThat(awaitBlockedBackendPid(holder, holderPid, "advisory")).isEqualTo(contenderPid);
        } finally {
          holder.rollback();
        }

        contention.get(5, TimeUnit.SECONDS);
      }
    }
  }

  private Connection openUnpooledConnection() throws SQLException {
    return DriverManager.getConnection(
        connectionDetails.getJdbcUrl(),
        connectionDetails.getUsername(),
        connectionDetails.getPassword());
  }

  private static void readBackendActivity(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var activity = statement.executeQuery("SELECT count(*) FROM pg_stat_activity")) {
      assertThat(activity.next()).isTrue();
    }
  }

  private static void lockAdvisoryKey(Connection connection, long key) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      statement.setLong(1, key);
      statement.execute();
    }
  }
}
