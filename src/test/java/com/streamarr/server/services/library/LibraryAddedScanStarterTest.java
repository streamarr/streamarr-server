package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.events.library.LibraryAddedEvent;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Tag("UnitTest")
@DisplayName("Library Added Scan Starter Tests")
class LibraryAddedScanStarterTest {

  @Test
  @DisplayName("Should start the first scan for the added library after commit")
  void shouldStartFirstScanForAddedLibraryAfterCommit() {
    var scans = new CopyOnWriteArrayList<UUID>();
    var starter = new LibraryAddedScanStarter(scans::add);
    var libraryId = UUID.randomUUID();

    starter.onLibraryAdded(new LibraryAddedEvent(libraryId, "file:///movies"));

    assertThat(scans).containsExactly(libraryId);
  }

  @Test
  @DisplayName("Should listen only after the adding transaction commits")
  void shouldListenOnlyAfterAddingTransactionCommits() throws NoSuchMethodException {
    var listener =
        LibraryAddedScanStarter.class
            .getMethod("onLibraryAdded", LibraryAddedEvent.class)
            .getAnnotation(TransactionalEventListener.class);

    assertThat(listener).isNotNull();
    assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
  }
}
