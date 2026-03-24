package com.streamarr.server.services.pagination;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Optional;

public final class MediaFilterComparator {

  private static final String EXCLUDED_FIELD = "previousSortFieldValue";

  private MediaFilterComparator() {}

  public static Optional<String> findMismatch(MediaFilter cursorFilter, MediaFilter currentFilter) {
    if (cursorFilter.equals(currentFilter)) {
      return Optional.empty();
    }

    for (Field field : MediaFilter.class.getDeclaredFields()) {
      if (EXCLUDED_FIELD.equals(field.getName())) {
        continue;
      }

      field.setAccessible(true);

      try {
        var cursorValue = field.get(cursorFilter);
        var currentValue = field.get(currentFilter);

        if (!Objects.equals(cursorValue, currentValue)) {
          return Optional.of(
              field.getName() + ": was '" + cursorValue + "' but is now '" + currentValue + "'");
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("Cannot access field: " + field.getName(), e);
      }
    }

    return Optional.of("Cursor filter does not match current filter");
  }
}
