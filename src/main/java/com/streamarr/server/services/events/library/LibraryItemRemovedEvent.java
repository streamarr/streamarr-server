package com.streamarr.server.services.events.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.MediaType;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record LibraryItemRemovedEvent(
    @NonNull UUID libraryId, @NonNull UUID itemId, @NonNull MediaType kind)
    implements LibraryItemChangedEvent {
  public static LibraryItemRemovedEvent of(
      @NonNull BaseCollectable<?> item, @NonNull MediaType kind) {
    return new LibraryItemRemovedEvent(item.getLibrary().getId(), item.getId(), kind);
  }
}
