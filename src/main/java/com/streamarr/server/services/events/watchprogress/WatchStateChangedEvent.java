package com.streamarr.server.services.events.watchprogress;

import java.util.Set;
import java.util.UUID;
import lombok.NonNull;

/**
 * One committed write changed the Profile's watch state. {@code itemIds} names the movies and
 * episodes whose watched status, resume position or last-watched time may have changed.
 */
public record WatchStateChangedEvent(@NonNull UUID profileId, @NonNull Set<UUID> itemIds) {

  public WatchStateChangedEvent {
    itemIds = Set.copyOf(itemIds);
  }
}
