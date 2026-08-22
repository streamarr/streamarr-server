package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ImageSize;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

  public static int backendPid(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      return result.getInt(1);
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
}
