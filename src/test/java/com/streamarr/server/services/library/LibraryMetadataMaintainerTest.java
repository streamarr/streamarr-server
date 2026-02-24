package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
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
@DisplayName("Library Metadata Maintainer Tests")
class LibraryMetadataMaintainerTest {

  private final FakeLibraryMetadataRepository fakeMetadataRepository =
      new FakeLibraryMetadataRepository();
  private final MutexFactoryProvider mutexFactoryProvider = new MutexFactoryProvider();
  private DSLContext mockContext;
  private LibraryMetadataMaintainer maintainer;

  private final UUID libraryId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    mockContext = mock(DSLContext.class);

    maintainer =
        new LibraryMetadataMaintainer(
            mockContext,
            fakeMetadataRepository,
            noOpTransactionTemplate(),
            _ -> false,
            mutexFactoryProvider);

    fakeMetadataRepository.deleteAll();
  }

  @Test
  @DisplayName("Should skip recalculation when item processed during active scan")
  void shouldSkipRecalculationWhenItemProcessedDuringActiveScan() {
    var scanningMaintainer =
        new LibraryMetadataMaintainer(
            mockContext,
            fakeMetadataRepository,
            noOpTransactionTemplate(),
            _ -> true,
            mutexFactoryProvider);

    scanningMaintainer.onItemProcessed(new ItemProcessedEvent(libraryId));

    var metadata = fakeMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
    assertThat(metadata).isEmpty();
  }

  @Test
  @DisplayName("Should recalculate on scan completed even during active scan")
  void shouldRecalculateOnScanCompletedEvenDuringActiveScan() {
    when(mockContext.select(any(), any())).thenThrow(new RuntimeException("query would run"));

    var scanningMaintainer =
        new LibraryMetadataMaintainer(
            mockContext,
            fakeMetadataRepository,
            noOpTransactionTemplate(),
            _ -> true,
            mutexFactoryProvider);

    assertThatNoException()
        .isThrownBy(() -> scanningMaintainer.onScanCompleted(new ScanCompletedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should not propagate exception when scan completed recalculation fails")
  void shouldNotPropagateExceptionWhenScanCompletedRecalculationFails() {
    when(mockContext.select(any(), any())).thenThrow(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> maintainer.onScanCompleted(new ScanCompletedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should not propagate exception when item processed recalculation fails")
  void shouldNotPropagateExceptionWhenItemProcessedRecalculationFails() {
    when(mockContext.select(any(), any())).thenThrow(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> maintainer.onItemProcessed(new ItemProcessedEvent(libraryId)));
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
