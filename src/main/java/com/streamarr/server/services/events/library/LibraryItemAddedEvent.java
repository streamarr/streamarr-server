package com.streamarr.server.services.events.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.MediaType;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record LibraryItemAddedEvent(
    @NonNull UUID libraryId, @NonNull UUID itemId, @NonNull MediaType kind)
    implements LibraryItemChangedEvent {
  public static LibraryItemAddedEvent of(
      @NonNull BaseCollectable<?> item, @NonNull MediaType kind) {
    return new LibraryItemAddedEvent(item.getLibrary().getId(), item.getId(), kind);
  }
}
