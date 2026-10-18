package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Library Metadata Listener Tests")
class LibraryMetadataListenerTest {

  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final FakeLibraryMetadataRepository libraryMetadata = new FakeLibraryMetadataRepository();
  private final LibraryMetadataListener listener =
      new LibraryMetadataListener(
          new LetterIndexRecalculator(libraryMetadata, new MutexFactoryProvider(), _ -> {}),
          libraryOperations,
          new TransactionTemplate(new FakeTransactionManager()));
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
  @DisplayName("Should skip recalculation when the library of a processed item no longer exists")
  void shouldSkipRecalculationWhenTheLibraryOfAProcessedItemNoLongerExists() {
    listener.onItemProcessed(new ItemProcessedEvent(UUID.randomUUID()));

    assertThat(libraryMetadata.letterCountReplacements()).isEmpty();
  }

  @Test
  @DisplayName("Should not propagate exception when item processed recalculation fails")
  void shouldNotPropagateExceptionWhenItemProcessedRecalculationFails() {
    libraryMetadata.failLetterCountReplacementsWith(new RuntimeException("DB error"));

    assertThatNoException()
        .isThrownBy(() -> listener.onItemProcessed(new ItemProcessedEvent(libraryId)));
  }
}
