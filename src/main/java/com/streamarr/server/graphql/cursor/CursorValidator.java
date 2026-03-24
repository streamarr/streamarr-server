package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.lang.reflect.Field;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class CursorValidator {

  private static final String EXCLUDED_FIELD = "previousSortFieldValue";

  public void validateCursorAgainstFilter(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    var cursorFilter = decodedOptions.getMediaFilter();

    if (cursorFilter.equals(filter)) {
      return;
    }

    throw new InvalidCursorException(buildMismatchMessage(cursorFilter, filter));
  }

  private String buildMismatchMessage(MediaFilter cursorFilter, MediaFilter currentFilter) {
    for (Field field : MediaFilter.class.getDeclaredFields()) {
      if (EXCLUDED_FIELD.equals(field.getName())) {
        continue;
      }

      field.setAccessible(true);

      try {
        var cursorValue = field.get(cursorFilter);
        var currentValue = field.get(currentFilter);

        if (!Objects.equals(cursorValue, currentValue)) {
          return "Prior query "
              + field.getName()
              + " was '"
              + cursorValue
              + "' but new query "
              + field.getName()
              + " is '"
              + currentValue
              + "'";
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("Cannot access field: " + field.getName(), e);
      }
    }

    return "Cursor filter does not match current filter";
  }
}
