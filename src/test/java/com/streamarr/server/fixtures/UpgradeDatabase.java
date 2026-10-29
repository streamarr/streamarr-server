package com.streamarr.server.fixtures;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * A database of its own in the shared PostgreSQL container, so an upgrade test can start the server
 * on stored results from before the upgrade without changing the other test databases. Closing it
 * drops the database.
 */
public record UpgradeDatabase(
    JdbcConnectionDetails connectionDetails, String name, HikariDataSource dataSource)
    implements AutoCloseable {

  public static UpgradeDatabase create(JdbcConnectionDetails connectionDetails)
      throws SQLException {
    var name = "probe_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = connect(connectionDetails);
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }

    var config = new HikariConfig();
    var url = connectionDetails.getJdbcUrl();
    var query = url.indexOf('?');
    var suffix = query < 0 ? "" : url.substring(query);
    config.setJdbcUrl(url.substring(0, url.lastIndexOf('/') + 1) + name + suffix);
    config.setUsername(connectionDetails.getUsername());
    config.setPassword(connectionDetails.getPassword());
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(0);
    return new UpgradeDatabase(connectionDetails, name, new HikariDataSource(config));
  }

  private static Connection connect(JdbcConnectionDetails details) throws SQLException {
    return DriverManager.getConnection(
        details.getJdbcUrl(), details.getUsername(), details.getPassword());
  }

  @Override
  public void close() throws SQLException {
    dataSource.close();
    try (var connection = connect(connectionDetails);
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE " + name + " WITH (FORCE)");
    }
  }
}
