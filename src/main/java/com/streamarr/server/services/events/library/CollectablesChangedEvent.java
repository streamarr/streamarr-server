package com.streamarr.server.services.events.library;

import java.util.Set;
import java.util.UUID;

/** Changes to collectable fields may change their top-level library item. */
public record CollectablesChangedEvent(Set<UUID> itemIds) {
  public CollectablesChangedEvent {
    itemIds = Set.copyOf(itemIds);
  }
}
