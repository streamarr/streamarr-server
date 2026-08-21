package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.events.library.LibraryAddedEvent;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library Added Watch Starter Tests")
class LibraryAddedWatchStarterTest {

  @Test
  @DisplayName("Should start directory watching when a library event is received")
  void shouldStartDirectoryWatchingWhenLibraryEventIsReceived() {
    var watchedPaths = new CopyOnWriteArrayList<String>();
    var starter = new LibraryAddedWatchStarter(watchedPaths::add);
    var filepathUri = "file:///movies";

    starter.onLibraryAdded(new LibraryAddedEvent(UUID.randomUUID(), filepathUri));

    assertThat(watchedPaths).containsExactly(filepathUri);
  }
}
