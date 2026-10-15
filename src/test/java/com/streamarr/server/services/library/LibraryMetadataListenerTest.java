package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
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
  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final UUID libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();

  @Test
  @DisplayName("Should skip recalculation when an item is processed while a library operation runs")
  void shouldSkipRecalculationWhenAnItemIsProcessedWhileALibraryOperationRuns() {
    libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId));
    var tripwireContext =
        mock(
            DSLContext.class,
            invocation -> {
              throw new AssertionError("recalculation should not have been attempted");
            });

    var listener =
        new LibraryMetadataListener(
            tripwireContext, noOpTransactionTemplate(), libraryOperations, mutexFactoryProvider);

    assertThatNoException()
        .isThrownBy(() -> listener.onItemProcessed(new ItemProcessedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should recalculate when a scan completes while its library operation runs")
  void shouldRecalculateWhenAScanCompletesWhileItsLibraryOperationRuns() {
    libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId));
    var tripwireContext =
        mock(
            DSLContext.class,
            invocation -> {
              throw new AssertionError("recalculation was attempted");
            });

    var listener =
        new LibraryMetadataListener(
            tripwireContext, noOpTransactionTemplate(), libraryOperations, mutexFactoryProvider);

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
            failingContext, noOpTransactionTemplate(), libraryOperations, mutexFactoryProvider);

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
            failingContext, noOpTransactionTemplate(), libraryOperations, mutexFactoryProvider);

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
