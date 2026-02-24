package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.library.events.ItemProcessedEvent;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
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
  private DSLContext mockContext;
  private LibraryMetadataListener listener;

  private final UUID libraryId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    mockContext = mock(DSLContext.class);

    listener =
        new LibraryMetadataListener(
            mockContext, noOpTransactionTemplate(), _ -> false, mutexFactoryProvider);
  }

  @Test
  @DisplayName("Should skip recalculation when item processed during active scan")
  void shouldSkipRecalculationWhenItemProcessedDuringActiveScan() {
    when(mockContext.select(any(), any()))
        .thenThrow(new AssertionError("recalculation should not have been attempted"));

    var scanningListener =
        new LibraryMetadataListener(
            mockContext, noOpTransactionTemplate(), _ -> true, mutexFactoryProvider);

    scanningListener.onItemProcessed(new ItemProcessedEvent(libraryId));
  }

  @Test
  @DisplayName("Should recalculate on scan completed even during active scan")
  void shouldRecalculateOnScanCompletedEvenDuringActiveScan() {
    when(mockContext.select(any(), any()))
        .thenThrow(new AssertionError("recalculation was attempted"));

    var scanningListener =
        new LibraryMetadataListener(
            mockContext, noOpTransactionTemplate(), _ -> true, mutexFactoryProvider);

    assertThatThrownBy(
            () -> scanningListener.onScanCompleted(new ScanCompletedEvent(libraryId)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("Should not propagate exception when scan completed recalculation fails")
  void shouldNotPropagateExceptionWhenScanCompletedRecalculationFails() {
    when(mockContext.select(any(), any())).thenThrow(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> listener.onScanCompleted(new ScanCompletedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should not propagate exception when item processed recalculation fails")
  void shouldNotPropagateExceptionWhenItemProcessedRecalculationFails() {
    when(mockContext.select(any(), any())).thenThrow(new RuntimeException("DB error"));

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
