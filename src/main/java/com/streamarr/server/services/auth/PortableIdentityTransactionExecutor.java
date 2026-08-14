package com.streamarr.server.services.auth;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class PortableIdentityTransactionExecutor {

  private static final int MAX_ATTEMPTS = 3;
  private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01");

  private final TransactionTemplate transactionTemplate;

  public PortableIdentityTransactionExecutor(PlatformTransactionManager transactionManager) {
    transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public <T> T execute(Supplier<T> operation) {
    for (var attempt = 1; ; attempt++) {
      try {
        return transactionTemplate.execute(_ -> operation.get());
      } catch (RuntimeException exception) {
        var retryableSqlState = retryableSqlState(exception);
        if (attempt == MAX_ATTEMPTS || retryableSqlState.isEmpty()) {
          throw exception;
        }
        var backoffMillis = ThreadLocalRandom.current().nextLong(5, 21) * attempt;
        log.warn(
            "Retrying portable identity transaction after SQLSTATE {} with {} ms backoff (attempt"
                + " {}/{}).",
            retryableSqlState.orElseThrow(),
            backoffMillis,
            attempt,
            MAX_ATTEMPTS);
        try {
          TimeUnit.MILLISECONDS.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw exception;
        }
      }
    }
  }

  public void execute(Runnable operation) {
    execute(
        () -> {
          operation.run();
          return null;
        });
  }

  private Optional<String> retryableSqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (!(cause instanceof SQLException sqlException)) {
        continue;
      }

      for (var candidate = sqlException;
          candidate != null;
          candidate = candidate.getNextException()) {
        var sqlState = candidate.getSQLState();
        if (sqlState != null && RETRYABLE_SQL_STATES.contains(sqlState)) {
          return Optional.of(sqlState);
        }
      }
    }
    return Optional.empty();
  }
}
