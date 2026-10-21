package com.streamarr.server.graphql.cursor;

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
      var decoded = objectMapper.readValue(jsonStr, MediaPaginationOptions.class);
      return withTypedSortValue(decoded).toBuilder().paginationOptions(options).build();

    } catch (Exception _) {
      var msg =
          "Could not decode cursor '"
              + cursor
              + "' into "
              + MediaPaginationOptions.class.getSimpleName();
      throw new InvalidCursorException(msg);
    }
  }

  private static MediaPaginationOptions withTypedSortValue(MediaPaginationOptions decoded) {
    var filter = decoded.getMediaFilter();
    if (!namesItemSortAndDirection(decoded)) {
      throw new IllegalArgumentException("A media cursor names its item, sort and direction.");
    }

    var sortValue = filter.getSortBy().readCursorValue(filter.getPreviousSortFieldValue());
    return decoded.toBuilder()
        .mediaFilter(filter.toBuilder().previousSortFieldValue(sortValue).build())
        .build();
  }

  private static boolean namesItemSortAndDirection(MediaPaginationOptions decoded) {
    var filter = decoded.getMediaFilter();
    return decoded.getCursorId().isPresent()
        && filter != null
        && filter.getSortBy() != null
        && filter.getSortDirection() != null;
  }
}
