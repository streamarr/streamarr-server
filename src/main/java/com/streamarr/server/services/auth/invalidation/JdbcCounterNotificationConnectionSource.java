package com.streamarr.server.services.auth.invalidation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.PGProperty;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JdbcCounterNotificationConnectionSource implements CounterNotificationConnectionSource {

  private static final String LISTEN_COUNTER_CHANNEL_SQL =
      "LISTEN " + CounterNotificationPayload.CHANNEL;
  private static final String LIVENESS_PROBE_SQL = "SELECT 1";
  private static final int SOCKET_TIMEOUT_SECONDS = 10;
  private static final int CONNECT_TIMEOUT_SECONDS = 10;

  private final JdbcConnectionDetails connectionDetails;

  @Override
  public CounterNotificationConnection open() {
    try {
      return new JdbcCounterNotificationConnection(
          DriverManager.getConnection(connectionDetails.getJdbcUrl(), connectionProperties()));
    } catch (SQLException e) {
      throw new CounterNotificationConnectionException(
          "Failed to open counter notification connection.", e);
    }
  }

  private Properties connectionProperties() {
    var properties = new Properties();
    if (connectionDetails.getUsername() != null) {
      PGProperty.USER.set(properties, connectionDetails.getUsername());
    }
    if (connectionDetails.getPassword() != null) {
      PGProperty.PASSWORD.set(properties, connectionDetails.getPassword());
    }
    // A LISTEN connection idles for its lifetime; keepalive and bounded timeouts turn a
    // black-holed socket into an error instead of eternal silence.
    PGProperty.TCP_KEEP_ALIVE.set(properties, true);
    PGProperty.SOCKET_TIMEOUT.set(properties, SOCKET_TIMEOUT_SECONDS);
    PGProperty.CONNECT_TIMEOUT.set(properties, CONNECT_TIMEOUT_SECONDS);
    return properties;
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
      probeLiveness();
      try {
        return Arrays.stream(connection.unwrap(PGConnection.class).getNotifications(pollTimeoutMs))
            .map(PGNotification::getParameter)
            .toList();
      } catch (SQLException e) {
        throw new CounterNotificationConnectionException(
            "Failed to poll counter notifications.", e);
      }
    }

    /**
     * getNotifications only reads already-buffered socket data, so a half-open connection reports
     * nothing forever. This round trip forces the failure to surface, bounded by socketTimeout —
     * pgjdbc's documented LISTEN pattern.
     */
    private void probeLiveness() {
      try (var statement = connection.createStatement()) {
        statement.execute(LIVENESS_PROBE_SQL);
      } catch (SQLException e) {
        throw new CounterNotificationConnectionException(
            "Counter notification connection failed the liveness probe.", e);
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
