package com.streamarr.server.services.streaming;

import java.sql.SQLException;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class StreamSessionTransactionRetry {

  private static final int MAX_ATTEMPTS = 3;
  private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "40001");

  private final IntConsumer backoff;

  public StreamSessionTransactionRetry() {
    this(StreamSessionTransactionRetry::sleep);
  }

  StreamSessionTransactionRetry(IntConsumer backoff) {
    this.backoff = backoff;
  }

  public <T> T execute(Supplier<T> transaction) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "Stream-session retries require a non-transactional caller");
    }
    for (var attempt = 1; ; attempt++) {
      try {
        return transaction.get();
      } catch (RuntimeException exception) {
        if (attempt == MAX_ATTEMPTS || !isRetryable(exception)) {
          throw exception;
        }
        backoff.accept(attempt);
      }
    }
  }

  private boolean isRetryable(Throwable throwable) {
    for (var cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && RETRYABLE_SQL_STATES.contains(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static void sleep(int attempt) {
    try {
      Thread.sleep(25L * attempt);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during stream transaction retry", exception);
    }
  }
}
