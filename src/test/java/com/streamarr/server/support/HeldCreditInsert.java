package com.streamarr.server.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Another media title's open save: a dedicated transaction that issues the same {@code INSERT ...
 * ON CONFLICT (source_id) DO NOTHING} as the credit repositories and keeps its rows uncommitted
 * until {@link #commit()}. Closing rolls an uncommitted transaction back.
 *
 * <p>The transaction raises its own {@code deadlock_timeout} so that, when a wait-for cycle forms,
 * PostgreSQL aborts the other transaction rather than this one. Its {@code lock_timeout} bounds a
 * wait that no deadlock check resolves.
 */
public final class HeldCreditInsert implements AutoCloseable {

  public enum CreditTable {
    PERSON,
    GENRE,
    COMPANY;

    private String insertSql() {
      return "INSERT INTO "
          + name().toLowerCase(Locale.ROOT)
          + " (source_id, name) VALUES (?, ?) ON CONFLICT (source_id) DO NOTHING";
    }
  }

  private final DataSource dataSource;
  private final Connection connection;
  private final int backendPid;
  private boolean committed;

  private HeldCreditInsert(DataSource dataSource, Connection connection, int backendPid) {
    this.dataSource = dataSource;
    this.connection = connection;
    this.backendPid = backendPid;
  }

  public static HeldCreditInsert open(DataSource dataSource) throws SQLException {
    var connection = dataSource.getConnection();
    try {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("SET LOCAL deadlock_timeout = '1min'");
        statement.execute("SET LOCAL lock_timeout = '10s'");
      }

      return new HeldCreditInsert(
          dataSource, connection, PostgresLockTestSupport.backendPid(connection));
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }
  }

  /** Inserts one credit row, waiting first for any open transaction that inserted the same key. */
  public void insert(CreditTable table, String sourceId) throws SQLException {
    try (var statement = connection.prepareStatement(table.insertSql())) {
      statement.setString(1, sourceId);
      statement.setString(2, "Held " + sourceId);
      statement.executeUpdate();
    }
  }

  /** Waits until another transaction waits for this one to end. */
  public void awaitWaitingTransaction() throws SQLException {
    try (var observer = dataSource.getConnection()) {
      PostgresLockTestSupport.awaitBlockedBackendPid(observer, backendPid, "transactionid");
    }
  }

  public void commit() throws SQLException {
    connection.commit();
    committed = true;
  }

  @Override
  public void close() throws SQLException {
    try {
      if (!committed) {
        connection.rollback();
      }
    } finally {
      connection.close();
    }
  }
}
