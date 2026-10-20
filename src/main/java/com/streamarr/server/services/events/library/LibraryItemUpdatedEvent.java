package com.streamarr.server.services.events.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.MediaType;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record LibraryItemUpdatedEvent(
    @NonNull UUID libraryId,
    @NonNull UUID itemId,
    @NonNull MediaType kind,
    @NonNull LibraryItemAudience audience)
    implements LibraryItemChangedEvent {
  public static LibraryItemUpdatedEvent forEveryProfile(
      @NonNull BaseCollectable<?> item, @NonNull MediaType kind) {
    return new LibraryItemUpdatedEvent(
        item.getLibrary().getId(), item.getId(), kind, LibraryItemAudience.everyProfile());
  }
}
