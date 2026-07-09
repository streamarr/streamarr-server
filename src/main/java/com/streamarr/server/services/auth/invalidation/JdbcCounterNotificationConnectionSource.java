package com.streamarr.server.services.auth.invalidation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JdbcCounterNotificationConnectionSource implements CounterNotificationConnectionSource {

  private final JdbcConnectionDetails connectionDetails;

  @Override
  public CounterNotificationConnection open() throws SQLException {
    return new JdbcCounterNotificationConnection(
        DriverManager.getConnection(
            connectionDetails.getJdbcUrl(),
            connectionDetails.getUsername(),
            connectionDetails.getPassword()));
  }

  private record JdbcCounterNotificationConnection(Connection connection)
      implements CounterNotificationConnection {

    @Override
    public void listen(String channel) throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.execute("LISTEN " + channel);
      }
    }

    @Override
    public List<String> notifications(int pollTimeoutMs) throws SQLException {
      return Arrays.stream(connection.unwrap(PGConnection.class).getNotifications(pollTimeoutMs))
          .map(notification -> notification.getParameter())
          .toList();
    }

    @Override
    public void close() throws SQLException {
      connection.close();
    }
  }
}
