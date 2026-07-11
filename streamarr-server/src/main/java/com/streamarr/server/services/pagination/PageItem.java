package com.streamarr.server.services.pagination;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public record PageItem<T>(T item, Object sortValue) {

  private static final Set<Class<?>> ALLOWED_SORT_TYPES =
      Set.of(String.class, Integer.class, Long.class, LocalDate.class, Instant.class);

  public PageItem {
    if (sortValue != null && ALLOWED_SORT_TYPES.stream().noneMatch(t -> t.isInstance(sortValue))) {
      throw new IllegalArgumentException(
          "Unsupported sort value type: " + sortValue.getClass().getName());
    }
  }
}
