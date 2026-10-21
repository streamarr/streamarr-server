package com.streamarr.server.services.events.library;

import com.streamarr.server.domain.media.MediaType;
import java.util.UUID;

/** IDs survive deletion; entity fields are resolved from committed state by each subscriber. */
public sealed interface LibraryItemChangedEvent
    permits LibraryItemAddedEvent, LibraryItemUpdatedEvent, LibraryItemRemovedEvent {
  UUID libraryId();

  UUID itemId();

  MediaType kind();
}
