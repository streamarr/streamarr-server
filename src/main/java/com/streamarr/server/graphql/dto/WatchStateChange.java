package com.streamarr.server.graphql.dto;

import java.util.Set;
import java.util.UUID;
import lombok.NonNull;

/**
 * One committed watch-state change for one Profile. {@code itemIds} names the movies and episodes
 * the change touched; each subscriber resolves them, with their seasons and series, when the change
 * is delivered. The ids are not in the schema.
 */
public record WatchStateChange(@NonNull Set<UUID> itemIds) {

  public WatchStateChange {
    itemIds = Set.copyOf(itemIds);
  }
}
