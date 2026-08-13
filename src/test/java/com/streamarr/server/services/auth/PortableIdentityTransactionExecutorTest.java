package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@Tag("UnitTest")
@DisplayName("Portable Identity Transaction Executor Tests")
class PortableIdentityTransactionExecutorTest {

  private final PortableIdentityTransactionExecutor executor =
      new PortableIdentityTransactionExecutor(new NoOpTransactionManager());

  @Test
  @DisplayName("Should retry the complete transaction after serialization failure")
  void shouldRetryCompleteTransactionAfterSerializationFailure() {
    var attempts = new AtomicInteger();

    var result =
        executor.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw failure("40001");
              }
              return "committed";
            });

    assertThat(result).isEqualTo("committed");
    assertThat(attempts).hasValue(3);
  }

  @Test
  @DisplayName("Should retry the complete transaction after deadlock detection")
  void shouldRetryCompleteTransactionAfterDeadlockDetection() {
    var attempts = new AtomicInteger();

    var result =
        executor.execute(
            () -> {
              if (attempts.incrementAndGet() == 1) {
                throw failure("40P01");
              }
              return "committed";
            });

    assertThat(result).isEqualTo("committed");
    assertThat(attempts).hasValue(2);
  }

  @Test
  @DisplayName("Should not retry a portable identity constraint violation")
  void shouldNotRetryPortableIdentityConstraintViolation() {
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw failure("23514");
                    }))
        .isInstanceOf(DataAccessResourceFailureException.class);

    assertThat(attempts).hasValue(1);
  }

  private DataAccessResourceFailureException failure(String sqlState) {
    return new DataAccessResourceFailureException(
        "Injected database failure", new SQLException("Injected database failure", sqlState));
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // no resource to begin
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // no resource to commit
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // no resource to roll back
    }
  }
}
