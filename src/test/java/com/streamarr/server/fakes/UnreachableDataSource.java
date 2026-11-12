package com.streamarr.server.fakes;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * A database that refuses every connection. Building a db-scheduler client asks the database for
 * its product name; without an answer the client falls back to its default SQL dialect.
 */
public final class UnreachableDataSource extends AbstractDataSource {

  @Override
  public Connection getConnection() throws SQLException {
    throw new SQLException("A unit test has no database");
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return getConnection();
  }
}
