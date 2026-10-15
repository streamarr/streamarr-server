package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library Metadata Listener Tests")
class LibraryMetadataListenerTest {

  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final FakeLibraryMetadataRepository libraryMetadata = new FakeLibraryMetadataRepository();
  private final LibraryMetadataListener listener =
      new LibraryMetadataListener(
          new LetterIndexRecalculator(libraryMetadata, new MutexFactoryProvider()),
          libraryOperations);
  private final UUID libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();

  @Test
  @DisplayName("Should skip recalculation when an item is processed while a library operation runs")
  void shouldSkipRecalculationWhenAnItemIsProcessedWhileALibraryOperationRuns() {
    libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId));

    listener.onItemProcessed(new ItemProcessedEvent(libraryId));

    assertThat(libraryMetadata.letterCountReplacements()).isEmpty();
  }

  @Test
  @DisplayName("Should recalculate when an item is processed while no library operation runs")
  void shouldRecalculateWhenAnItemIsProcessedWhileNoLibraryOperationRuns() {
    listener.onItemProcessed(new ItemProcessedEvent(libraryId));

    assertThat(libraryMetadata.letterCountReplacements()).containsExactly(libraryId);
  }

  @Test
  @DisplayName("Should recalculate when a scan completes while its library operation runs")
  void shouldRecalculateWhenAScanCompletesWhileItsLibraryOperationRuns() {
    libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId));

    listener.onScanCompleted(new ScanCompletedEvent(libraryId));

    assertThat(libraryMetadata.letterCountReplacements()).containsExactly(libraryId);
  }

  @Test
  @DisplayName("Should not propagate exception when scan completed recalculation fails")
  void shouldNotPropagateExceptionWhenScanCompletedRecalculationFails() {
    libraryMetadata.failLetterCountReplacementsWith(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> listener.onScanCompleted(new ScanCompletedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should not propagate exception when refresh ended recalculation fails")
  void shouldNotPropagateExceptionWhenRefreshEndedRecalculationFails() {
    libraryMetadata.failLetterCountReplacementsWith(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> listener.onRefreshEnded(new RefreshEndedEvent(libraryId)));
  }

  @Test
  @DisplayName("Should not propagate exception when item processed recalculation fails")
  void shouldNotPropagateExceptionWhenItemProcessedRecalculationFails() {
    libraryMetadata.failLetterCountReplacementsWith(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> listener.onItemProcessed(new ItemProcessedEvent(libraryId)));
  }
}
