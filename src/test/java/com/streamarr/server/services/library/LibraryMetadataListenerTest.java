package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.library.events.ItemProcessedEvent;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Library Metadata Listener Tests")
class LibraryMetadataListenerTest {

  private final MutexFactoryProvider mutexFactoryProvider = new MutexFactoryProvider();
  private final UUID libraryId = UUID.randomUUID();

  @Test
  @DisplayName("Should skip recalculation when item processed during active scan")
  void shouldSkipRecalculationWhenItemProcessedDuringActiveScan() {
    var tripwireContext =
        mock(
            DSLContext.class,
            invocation -> {
              throw new AssertionError("recalculation should not have been attempted");
            });

    var listener =
        new LibraryMetadataListener(
            tripwireContext, noOpTransactionTemplate(), _ -> true, mutexFactoryProvider);

    assertThatNoException()
        .isThrownBy(() -> listener.onItemProcessed(new ItemProcessedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should recalculate when scan completes during active scan")
  void shouldRecalculateOnScanCompletedEvenDuringActiveScan() {
    var tripwireContext =
        mock(
            DSLContext.class,
            invocation -> {
              throw new AssertionError("recalculation was attempted");
            });

    var listener =
        new LibraryMetadataListener(
            tripwireContext, noOpTransactionTemplate(), _ -> true, mutexFactoryProvider);

    var event = new ScanCompletedEvent(libraryId);

    assertThatThrownBy(() -> listener.onScanCompleted(event))
        .isInstanceOf(AssertionError.class)
        .hasMessage("recalculation was attempted");
  }

  @Test
  @DisplayName("Should not propagate exception when scan completed recalculation fails")
  void shouldNotPropagateExceptionWhenScanCompletedRecalculationFails() {
    var failingContext =
        mock(
            DSLContext.class,
            invocation -> {
              throw new RuntimeException("DB error");
            });

    var listener =
        new LibraryMetadataListener(
            failingContext, noOpTransactionTemplate(), _ -> false, mutexFactoryProvider);

    assertThatNoException()
        .isThrownBy(() -> listener.onScanCompleted(new ScanCompletedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should not propagate exception when item processed recalculation fails")
  void shouldNotPropagateExceptionWhenItemProcessedRecalculationFails() {
    var failingContext =
        mock(
            DSLContext.class,
            invocation -> {
              throw new RuntimeException("DB error");
            });

    var listener =
        new LibraryMetadataListener(
            failingContext, noOpTransactionTemplate(), _ -> false, mutexFactoryProvider);

    assertThatNoException()
        .isThrownBy(() -> listener.onItemProcessed(new ItemProcessedEvent(libraryId)));
  }

  private static TransactionTemplate noOpTransactionTemplate() {
    return new TransactionTemplate(
        new AbstractPlatformTransactionManager() {
          @Override
          protected Object doGetTransaction() {
            return new Object();
          }

          @Override
          protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op for test fake
          }

          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            // no-op for test fake
          }

          @Override
          protected void doRollback(DefaultTransactionStatus status) {
            // no-op for test fake
          }
        });
  }
}
