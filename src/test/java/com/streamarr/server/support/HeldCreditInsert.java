package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Another media title's open save: a dedicated transaction that issues the same {@code INSERT ...
 * ON CONFLICT (source_id) DO NOTHING} as the credit repositories and keeps its rows uncommitted
 * until {@link #commitAfterSaveWaits}. Closing rolls an uncommitted transaction back.
 *
 * <p>The transaction raises its own {@code deadlock_timeout} so that, when a wait-for cycle forms,
 * PostgreSQL aborts the other transaction rather than this one. Its {@code lock_timeout} bounds a
 * wait that no deadlock check resolves.
 */
public final class HeldCreditInsert implements AutoCloseable {

  private static final Duration SAVE_TIMEOUT = Duration.ofSeconds(15);

  public enum CreditTable {
    PERSON,
    GENRE,
    COMPANY;

    private String insertSql() {
      return "INSERT INTO "
          + tableName()
          + " (source_id, name) VALUES (?, ?) ON CONFLICT (source_id) DO NOTHING";
    }

    private String lockForRenameSql() {
      return "SELECT id FROM " + tableName() + " WHERE source_id = ? FOR NO KEY UPDATE";
    }

    private String tableName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** A statement the held transaction runs after the save starts waiting for it. */
  @FunctionalInterface
  public interface HeldStep {
    void run(HeldCreditInsert held) throws SQLException;
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

  /**
   * Takes the row lock that renaming an existing credit holds until commit. A real rename would
   * also write a new row version, which makes a later insert of the same source id wait here. The
   * lock alone plays a media title that renamed the credit after the save checked that the row
   * exists.
   */
  public void lockForRename(CreditTable table, String sourceId) throws SQLException {
    try (var statement = connection.prepareStatement(table.lockForRenameSql())) {
      statement.setString(1, sourceId);
      try (var lockedRow = statement.executeQuery()) {
        assertThat(lockedRow.next()).isTrue();
      }
    }
  }

  /**
   * Starts {@code save} on another thread and waits until the save waits for this transaction. Then
   * runs {@code lastHeldStep}, commits, and returns the save's result. Fails when the save fails or
   * does not finish within 15 seconds.
   */
  public <T> T commitAfterSaveWaits(Supplier<T> save, HeldStep lastHeldStep) throws SQLException {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var saveResult = CompletableFuture.supplyAsync(save, executor);

      awaitWaitingTransaction();
      lastHeldStep.run(this);
      commit();

      assertThat(saveResult).succeedsWithin(SAVE_TIMEOUT);
      return saveResult.join();
    }
  }

  private void awaitWaitingTransaction() throws SQLException {
    try (var observer = dataSource.getConnection()) {
      PostgresLockTestSupport.awaitBlockedBackendPid(observer, backendPid, "transactionid");
    }
  }

  private void commit() throws SQLException {
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
