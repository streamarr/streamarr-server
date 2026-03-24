package com.streamarr.server.rest.pagination;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class JsonApiCursorCodec {

  private final ObjectMapper objectMapper;

  public String encode(MediaPaginationOptions options, UUID cursorId, Object sortValue) {
    var cursorState =
        MediaPaginationOptions.builder()
            .cursorId(cursorId)
            .mediaFilter(
                options.getMediaFilter().toBuilder().previousSortFieldValue(sortValue).build())
            .build();

    try {
      var json = objectMapper.writeValueAsString(cursorState);
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to encode cursor", ex);
    }
  }

  public MediaPaginationOptions decode(PaginationOptions paginationOptions) {
    var cursor = paginationOptions.getCursor();

    if (cursor.isEmpty()) {
      throw new IllegalArgumentException("Cannot decode an empty cursor.");
    }

    try {
      var json = new String(Base64.getUrlDecoder().decode(cursor.get()), StandardCharsets.UTF_8);
      return objectMapper.readValue(json, MediaPaginationOptions.class).toBuilder()
          .paginationOptions(paginationOptions)
          .build();
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid cursor: " + cursor.get(), ex);
    }
  }

  private static final String EXCLUDED_FIELD = "previousSortFieldValue";

  public void validateCursorFilter(MediaPaginationOptions decoded, MediaFilter currentFilter) {
    var cursorFilter = decoded.getMediaFilter();

    if (cursorFilter.equals(currentFilter)) {
      return;
    }

    throw new IllegalArgumentException(buildMismatchMessage(cursorFilter, currentFilter));
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
          return "Cursor "
              + field.getName()
              + " was '"
              + cursorValue
              + "' but request "
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
