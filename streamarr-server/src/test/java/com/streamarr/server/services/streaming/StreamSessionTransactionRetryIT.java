package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Stream Session Transaction Retry Integration Tests")
class StreamSessionTransactionRetryIT extends AbstractIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("Should use a fresh completed transaction before each retry backoff")
  void shouldUseFreshCompletedTransactionBeforeEachRetryBackoff() {
    var attempts = new AtomicInteger();
    var transactionIds = new ArrayList<Long>();
    var backoffs = new ArrayList<Integer>();
    var transactionTemplate = new TransactionTemplate(transactionManager);
    var retry =
        new StreamSessionTransactionRetry(
            attempt -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              backoffs.add(attempt);
            });

    var result =
        retry.execute(
            () ->
                transactionTemplate.execute(
                    _ -> {
                      transactionIds.add(
                          dsl.select(
                                  DSL.function(
                                      DSL.name("txid_current"), SQLDataType.BIGINT.notNull()))
                              .fetchSingle()
                              .value1());
                      if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException(
                            "simulated serialization failure", new SQLException("test", "40001"));
                      }
                      return "committed";
                    }));

    assertThat(result).isEqualTo("committed");
    assertThat(transactionIds).hasSize(3).doesNotHaveDuplicates();
    assertThat(backoffs).containsExactly(1, 2);
  }

  @Test
  @DisplayName("Should reject an outer transaction before a retry attempt")
  void shouldRejectOuterTransactionBeforeRetryAttempt() {
    var attempts = new AtomicInteger();
    var outer = new TransactionTemplate(transactionManager);
    var inner = new TransactionTemplate(transactionManager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var retry =
        new StreamSessionTransactionRetry(
            _ -> {
              throw new AssertionError("backoff must not run for a rejected caller");
            });

    assertThatThrownBy(
            () ->
                outer.execute(
                    _ -> {
                      dsl.selectOne().fetchSingle();
                      return retry.execute(
                          () ->
                              inner.execute(
                                  _ -> {
                                    attempts.incrementAndGet();
                                    return "committed";
                                  }));
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(attempts).hasValue(0);
  }
}
