package com.streamarr.server.services.auth;

import java.sql.SQLException;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
        if (attempt == MAX_ATTEMPTS || !isRetryable(exception)) {
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

  private boolean isRetryable(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && RETRYABLE_SQL_STATES.contains(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
