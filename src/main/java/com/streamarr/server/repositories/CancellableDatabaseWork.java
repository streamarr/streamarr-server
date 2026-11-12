package com.streamarr.server.repositories;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.NonNull;

/**
 * Database work that another thread can cancel without breaking a pooled connection, such as work
 * that a shutdown must not wait for.
 *
 * <p>An interrupt closes the socket that a virtual thread reads, so interrupting work inside a
 * statement breaks the statement's connection: the pool discards the connection, a rollback on it
 * fails, and PostgreSQL goes on waiting for a lock that the statement waits for. {@link #cancel()}
 * therefore asks PostgreSQL to cancel a running statement, which ends the statement with SQLSTATE
 * 57014 and keeps the connection, and interrupts work only while it runs no statement, such as work
 * that waits for a pooled connection. The work must run its statements through jOOQ, which reports
 * when each statement starts and ends.
 */
public final class CancellableDatabaseWork {

  static final ScopedValue<Call> CURRENT_CALL = ScopedValue.newInstance();

  private final Set<Call> calls = ConcurrentHashMap.newKeySet();
  private volatile boolean cancelled;

  /**
   * Runs {@code work} on this thread and returns its result, which must not be null. Returns empty
   * when {@link #cancel()} cancelled the work, whatever the work threw then, and when the cancel
   * came before the work started. A failure of work that no cancel reached propagates.
   */
  public <T> Optional<T> call(@NonNull Supplier<T> work) {
    var call = new Call(Thread.currentThread());
    calls.add(call);
    try {
      if (cancelled) {
        return Optional.empty();
      }

      return Optional.of(ScopedValue.where(CURRENT_CALL, call).call(work::get));
    } catch (RuntimeException failure) {
      if (call.isCancelled()) {
        return Optional.empty();
      }

      throw failure;
    } finally {
      calls.remove(call);
    }
  }

  /**
   * Cancels every call that runs and every later one. Returns how many running calls it cancelled,
   * without waiting for PostgreSQL to receive the request that cancels a running statement.
   *
   * <p>Two rare cases escape the cancel. In both, the caller's wait for the work keeps its own
   * bound, and a write that commits is the one the work was asked for. A caller that cancels only
   * at shutdown meets them only during its shutdown.
   *
   * <ul>
   *   <li>A cancel that lands after jOOQ reports a statement and before the driver sends it is
   *       lost, in a window of microseconds at the statement's start. The statement runs on until
   *       its lock timeout, or until the pool closes its connection.
   *   <li>The driver stops waiting for a cancel request after its {@code cancelSignalTimeout}, 10 s
   *       by default. A request that reaches PostgreSQL later ends whatever statement then runs on
   *       that connection, which fails with SQLSTATE 57014 as a cancelled statement does.
   * </ul>
   */
  public int cancel() {
    cancelled = true;
    var cancelledCalls = 0;
    for (var call : calls) {
      call.cancel();
      cancelledCalls++;
    }

    return cancelledCalls;
  }

  static final class Call {

    private final Thread thread;
    private Statement statement;
    private boolean cancelled;

    private Call(Thread thread) {
      this.thread = thread;
    }

    synchronized void startStatement(Statement started) {
      if (cancelled) {
        // A statement, or the rollback that follows this refusal, would read its socket while the
        // interrupt from cancel is pending, and the interrupt would close the socket.
        Thread.interrupted();
        throw new CancelledStatementException();
      }

      statement = started;
    }

    synchronized void endStatement() {
      statement = null;
    }

    private synchronized boolean isCancelled() {
      return cancelled;
    }

    private synchronized void cancel() {
      cancelled = true;
      if (statement == null) {
        thread.interrupt();
        return;
      }

      // The driver sends the cancel request over a connection of its own and waits for PostgreSQL
      // to close it, for up to its cancelSignalTimeout of 10 s, so the canceller does not send it.
      var running = statement;
      Thread.ofVirtual().name("database-work-cancel").start(() -> requestCancel(running));
    }

    private static void requestCancel(Statement running) {
      try {
        running.cancel();
      } catch (SQLException _) {
        // The statement's connection has closed, which ended the statement too. The driver drops a
        // cancel request that cannot reach PostgreSQL, and the caller bounds its wait for the work.
      }
    }
  }

  static final class CancelledStatementException extends RuntimeException {

    private CancelledStatementException() {
      super("The database work was cancelled before this statement started");
    }
  }
}
