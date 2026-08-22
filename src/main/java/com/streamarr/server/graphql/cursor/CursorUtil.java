package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationOptions;
import graphql.relay.DefaultConnectionCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CursorUtil {

  private final ObjectMapper objectMapper;

  public DefaultConnectionCursor encodeKeysetCursor(UUID cursorId) {
    return new DefaultConnectionCursor(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cursorId.toString().getBytes(StandardCharsets.UTF_8)));
  }

  public KeysetPaginationOptions decodeKeysetCursor(PaginationOptions options) {
    var cursorId =
        options
            .getCursor()
            .map(
                cursor -> {
                  try {
                    var decoded =
                        new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                    return UUID.fromString(decoded);
                  } catch (IllegalArgumentException _) {
                    throw new InvalidCursorException("Cursor is not valid.");
                  }
                })
            .orElse(null);
    return KeysetPaginationOptions.builder().cursorId(cursorId).paginationOptions(options).build();
  }

  public DefaultConnectionCursor encodeMediaCursor(
      MediaPaginationOptions options, UUID cursorId, Object sortValue) {
    try {
      var cursorState =
          MediaPaginationOptions.builder()
              .cursorId(cursorId)
              .mediaFilter(
                  options.getMediaFilter().toBuilder().previousSortFieldValue(sortValue).build())
              .build();

      var jsonStr = objectMapper.writeValueAsString(cursorState);
      return new DefaultConnectionCursor(
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(jsonStr.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new InvalidCursorException(ex.getMessage());
    }
  }

  public MediaPaginationOptions decodeMediaCursor(PaginationOptions options) {

    var optionalCursor = options.getCursor();

    if (optionalCursor.isEmpty()) {
      throw new InvalidCursorException("Cannot decode an empty cursor.");
    }

    var cursor = optionalCursor.get();

    try {
      var jsonStr = new String(Base64.getUrlDecoder().decode(cursor));
      return objectMapper.readValue(jsonStr, MediaPaginationOptions.class).toBuilder()
          .paginationOptions(options)
          .build();

    } catch (Exception _) {
      var msg =
          "Could not decode cursor '"
              + cursor
              + "' into "
              + MediaPaginationOptions.class.getSimpleName();
      throw new InvalidCursorException(msg);
    }
  }
}
