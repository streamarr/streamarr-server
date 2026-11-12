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
   * Cancels every call that runs and every later one. Returns how many running calls it cancelled.
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

      try {
        statement.cancel();
      } catch (SQLException _) {
        // The statement has ended, or the cancel request could not reach PostgreSQL. The caller
        // bounds its own wait for the work.
      }
    }
  }

  static final class CancelledStatementException extends RuntimeException {

    private CancelledStatementException() {
      super("The database work was cancelled before this statement started");
    }
  }
}
