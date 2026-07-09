package com.streamarr.server.services.auth.invalidation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JdbcCounterNotificationConnectionSource implements CounterNotificationConnectionSource {

  private static final String LISTEN_COUNTER_CHANNEL_SQL = "LISTEN streamarr_counters";

  private final JdbcConnectionDetails connectionDetails;

  @Override
  public CounterNotificationConnection open() {
    try {
      return new JdbcCounterNotificationConnection(
          DriverManager.getConnection(
              connectionDetails.getJdbcUrl(),
              connectionDetails.getUsername(),
              connectionDetails.getPassword()));
    } catch (SQLException e) {
      throw new CounterNotificationConnectionException(
          "Failed to open counter notification connection.", e);
    }
  }

  private record JdbcCounterNotificationConnection(Connection connection)
      implements CounterNotificationConnection {

    @Override
    public void listen() {
      try (var statement = connection.createStatement()) {
        statement.execute(LISTEN_COUNTER_CHANNEL_SQL);
      } catch (SQLException e) {
        throw new CounterNotificationConnectionException(
            "Failed to listen for counter notifications.", e);
      }
    }

    @Override
    public List<String> notifications(int pollTimeoutMs) {
      try {
        return Arrays.stream(connection.unwrap(PGConnection.class).getNotifications(pollTimeoutMs))
            .map(PGNotification::getParameter)
            .toList();
      } catch (SQLException e) {
        throw new CounterNotificationConnectionException(
            "Failed to poll counter notifications.", e);
      }
    }

    @Override
    public void close() {
      try {
        connection.close();
      } catch (SQLException e) {
        throw new CounterNotificationConnectionException(
            "Failed to close counter notification connection.", e);
      }
    }
  }
}
