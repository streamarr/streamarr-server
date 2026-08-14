package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
  @DisplayName("Should retry when database reports deadlock through chained exception")
  void shouldRetryWhenDatabaseReportsDeadlockThroughChainedException() {
    var attempts = new AtomicInteger();

    var result =
        executor.execute(
            () -> {
              if (attempts.incrementAndGet() == 1) {
                throw chainedFailure("40P01");
              }
              return "committed";
            });

    assertThat(result).isEqualTo("committed");
    assertThat(attempts).hasValue(2);
  }

  @Test
  @DisplayName("Should log retryable database contention before retrying")
  void shouldLogRetryableDatabaseContentionBeforeRetrying() {
    var attempts = new AtomicInteger();
    var logger = (Logger) LoggerFactory.getLogger(PortableIdentityTransactionExecutor.class);
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);

    try {
      executor.execute(
          () -> {
            if (attempts.incrementAndGet() == 1) {
              throw failure("40P01");
            }
            return "committed";
          });
    } finally {
      logger.detachAppender(events);
    }

    assertThat(events.list)
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .containsIgnoringCase("retry")
                    .containsIgnoringCase("backoff")
                    .contains("40P01"));
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

  private DataAccessResourceFailureException chainedFailure(String sqlState) {
    var root = new SQLException("Batch failed");
    root.setNextException(new SQLException("Deadlock detected", sqlState));
    return new DataAccessResourceFailureException("Injected database failure", root);
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
