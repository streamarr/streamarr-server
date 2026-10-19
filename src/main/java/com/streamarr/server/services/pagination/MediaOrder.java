package com.streamarr.server.services.pagination;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record MediaOrder(
    @NonNull OrderMediaBy by,
    @NonNull SortDirection direction,
    @NonNull UUID itemId,
    Object value) {
  public static MediaOrder from(MediaFilter filter, UUID itemId, Object value) {
    return builder()
        .by(filter.getSortBy())
        .direction(filter.getSortDirection())
        .itemId(itemId)
        .value(value)
        .build();
  }

  public static MediaOrder newestWatchedFirst(UUID itemId, Instant lastWatchedAt) {
    return builder()
        .by(OrderMediaBy.LAST_WATCHED)
        .direction(SortDirection.DESC)
        .itemId(itemId)
        .value(lastWatchedAt)
        .build();
  }
}
