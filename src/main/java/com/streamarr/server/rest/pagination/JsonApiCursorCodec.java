package com.streamarr.server.rest.pagination;

import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaFilterComparator;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
      throw new InvalidPaginationArgumentException("Cannot decode an empty cursor.");
    }

    try {
      var json = new String(Base64.getUrlDecoder().decode(cursor.get()), StandardCharsets.UTF_8);
      return objectMapper.readValue(json, MediaPaginationOptions.class).toBuilder()
          .paginationOptions(paginationOptions)
          .build();
    } catch (Exception _) {
      throw new InvalidPaginationArgumentException("Invalid cursor: " + cursor.get());
    }
  }

  public void validateCursorFilter(MediaPaginationOptions decoded, MediaFilter currentFilter) {
    MediaFilterComparator.findMismatch(decoded.getMediaFilter(), currentFilter)
        .ifPresent(
            detail -> {
              throw new InvalidPaginationArgumentException("Cursor filter mismatch: " + detail);
            });
  }
}
