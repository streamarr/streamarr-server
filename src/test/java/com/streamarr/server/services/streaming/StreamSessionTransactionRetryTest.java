package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stream Session Transaction Retry Tests")
class StreamSessionTransactionRetryTest {

  @Test
  @DisplayName("Should retry deadlock transactions in fresh attempts with backoff between them")
  void shouldRetryDeadlockTransactionsWithBackoffBetweenAttempts() {
    var backoffs = new ArrayList<Integer>();
    var retry = new StreamSessionTransactionRetry(backoffs::add);
    var attempts = new AtomicInteger();

    var result =
        retry.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw databaseFailure("40P01");
              }
              return "committed";
            });

    assertThat(result).isEqualTo("committed");
    assertThat(attempts).hasValue(3);
    assertThat(backoffs).containsExactly(1, 2);
  }

  @Test
  @DisplayName("Should stop after three serialization failures")
  void shouldStopAfterThreeSerializationFailures() {
    var attempts = new AtomicInteger();
    var failure = databaseFailure("40001");
    var retry = new StreamSessionTransactionRetry(_ -> {});

    assertThatThrownBy(
            () ->
                retry.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(attempts).hasValue(3);
  }

  @Test
  @DisplayName("Should not retry a non-transient database failure")
  void shouldNotRetryNonTransientDatabaseFailure() {
    var attempts = new AtomicInteger();
    var failure = databaseFailure("23503");
    var retry = new StreamSessionTransactionRetry(_ -> {});

    assertThatThrownBy(
            () ->
                retry.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(attempts).hasValue(1);
  }

  @Test
  @DisplayName("Should preserve interrupt status when default retry backoff is interrupted")
  void shouldPreserveInterruptStatusWhenDefaultRetryBackoffIsInterrupted() {
    Thread.currentThread().interrupt();

    try {
      assertThatThrownBy(
              () ->
                  new StreamSessionTransactionRetry()
                      .execute(
                          () -> {
                            throw databaseFailure("40P01");
                          }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Interrupted during stream transaction retry")
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static IllegalStateException databaseFailure(String sqlState) {
    return new IllegalStateException("database failure", new SQLException("test", sqlState));
  }
}
