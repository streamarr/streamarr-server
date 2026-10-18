package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.LibraryCountsChangedEvent;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Recalculates a library's letter index: how many of its items start with each letter. */
@Slf4j
@Service
public class LetterIndexRecalculator {

  private final LibraryMetadataRepository libraryMetadataRepository;
  private final MutexFactory<String> mutexFactory;
  private final ApplicationEventPublisher events;

  public LetterIndexRecalculator(
      LibraryMetadataRepository libraryMetadataRepository,
      MutexFactoryProvider mutexFactoryProvider,
      ApplicationEventPublisher events) {
    this.libraryMetadataRepository = libraryMetadataRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
    this.events = events;
  }

  public void recalculateLetterIndex(UUID libraryId) {
    var mutex = mutexFactory.getMutex("library-metadata-" + libraryId);
    mutex.lock();
    try {
      var letters = libraryMetadataRepository.replaceLetterCounts(libraryId);
      events.publishEvent(new LibraryCountsChangedEvent(libraryId));
      log.info(
          "Recalculated letter index for library {}: {} distinct letters.", libraryId, letters);
    } finally {
      mutex.unlock();
    }
  }
}
