package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ImageSize;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import lombok.Builder;
import lombok.NonNull;

public final class PostgresLockTestSupport {

  private PostgresLockTestSupport() {}

  public static void lockArtworkRows(Connection connection, UUID entityId) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT id FROM image WHERE entity_id = ? FOR UPDATE")) {
      statement.setObject(1, entityId);
      try (var rows = statement.executeQuery()) {
        var lockedRows = 0;
        while (rows.next()) {
          lockedRows++;
        }

        assertThat(lockedRows).isEqualTo(ImageSize.values().length);
      }
    }
  }

  public static void lockAccountRow(Connection connection, UUID accountId) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT id FROM user_account WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, accountId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  /**
   * Takes one row's FOR UPDATE lock on a dedicated connection. Contenders block until the returned
   * hold is released or closed; closing rolls the holding transaction back.
   */
  public static HeldRowLock lockRow(RowLockTarget target) throws SQLException {
    var connection = target.dataSource().getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.prepareStatement(target.lockSql())) {
      statement.setObject(1, target.rowId());
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).as("%s row %s exists", target.table(), target.rowId()).isTrue();
      }
    } catch (SQLException | AssertionError failure) {
      connection.close();
      throw failure;
    }

    return new HeldRowLock(connection);
  }

  public static void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while holding a test lock.", exception);
    }
  }

  public static int backendPid(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      return result.getInt(1);
    }
  }

  public static String activeQuery(Connection observer, int backendPid) throws SQLException {
    try (var statement =
        observer.prepareStatement("SELECT query FROM pg_stat_activity WHERE pid = ?")) {
      statement.setInt(1, backendPid);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  public static int awaitBlockedBackendPid(
      Connection observer, int blockerPid, String expectedWaitEvent) {
    var blockedPid = new AtomicInteger();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              var candidate = blockedBackendPid(observer, blockerPid, expectedWaitEvent);
              candidate.ifPresent(blockedPid::set);
              return candidate.isPresent();
            });
    return blockedPid.get();
  }

  private static OptionalInt blockedBackendPid(
      Connection observer, int blockerPid, String expectedWaitEvent) throws SQLException {
    var sql =
        """
        SELECT pid
        FROM pg_stat_activity
        WHERE ? = ANY(pg_blocking_pids(pid))
          AND wait_event_type = 'Lock'
          AND (? IS NULL OR wait_event = ?)
        ORDER BY pid
        LIMIT 1
        """;
    try (var statement = observer.prepareStatement(sql)) {
      statement.setInt(1, blockerPid);
      statement.setString(2, expectedWaitEvent);
      statement.setString(3, expectedWaitEvent);
      try (var result = statement.executeQuery()) {
        return result.next() ? OptionalInt.of(result.getInt(1)) : OptionalInt.empty();
      }
    }
  }

  /** The row to lock; rows are addressed by {@code id} unless {@code keyColumn} says otherwise. */
  @Builder
  public record RowLockTarget(
      @NonNull DataSource dataSource,
      @NonNull String table,
      String keyColumn,
      @NonNull UUID rowId) {

    String lockSql() {
      var column = Objects.requireNonNullElse(keyColumn, "id");
      return "SELECT " + column + " FROM " + table + " WHERE " + column + " = ? FOR UPDATE";
    }
  }

  public static final class HeldRowLock implements AutoCloseable {

    private final Connection connection;
    private boolean released;

    private HeldRowLock(Connection connection) {
      this.connection = connection;
    }

    /** Rolls the holding transaction back so blocked contenders proceed; idempotent. */
    public void release() throws SQLException {
      if (released) {
        return;
      }

      released = true;
      connection.rollback();
    }

    @Override
    public void close() throws SQLException {
      try {
        release();
      } finally {
        connection.close();
      }
    }
  }
}
