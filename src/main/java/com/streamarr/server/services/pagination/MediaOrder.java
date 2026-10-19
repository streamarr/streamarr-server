package com.streamarr.server.services.pagination;

import java.util.UUID;
import lombok.Builder;

@Builder
public record MediaOrder(OrderMediaBy by, SortDirection direction, UUID itemId, Object value) {
  public static MediaOrder from(MediaFilter filter, UUID itemId, Object value) {
    return builder()
        .by(filter.getSortBy())
        .direction(filter.getSortDirection())
        .itemId(itemId)
        .value(value)
        .build();
  }
}
