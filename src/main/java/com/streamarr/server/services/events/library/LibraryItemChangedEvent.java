package com.streamarr.server.services.events.library;

import com.streamarr.server.domain.media.MediaType;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;

/** IDs survive deletion; entity fields are resolved from committed state by each subscriber. */
@Builder
public record LibraryItemChangedEvent(
    Change change, UUID libraryId, UUID itemId, MediaType kind, Optional<UUID> profileId) {
  public static class LibraryItemChangedEventBuilder {
    // Lombok reads this default from the generated build() method.
    @SuppressWarnings("java:S1068")
    private Optional<UUID> profileId = Optional.empty();
  }

  public boolean visibleTo(UUID selectedProfileId) {
    return profileId.map(selectedProfileId::equals).orElse(true);
  }

  public enum Change {
    ADDED,
    UPDATED,
    REMOVED
  }
}
