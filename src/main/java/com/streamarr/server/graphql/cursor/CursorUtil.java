package com.streamarr.server.graphql.cursor;

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

  private final ObjectMapper jacksonObjectMapper;

  public DefaultConnectionCursor encodeMediaCursor(
      MediaPaginationOptions mediaPaginationOptions, UUID cursorId, Object sortValue) {
    try {
      mediaPaginationOptions =
          MediaPaginationOptions.builder()
              .cursorId(cursorId)
              .mediaFilter(
                  mediaPaginationOptions.getMediaFilter().toBuilder()
                      .previousSortFieldValue(sortValue)
                      .build())
              .build();

      String jsonStr = jacksonObjectMapper.writeValueAsString(mediaPaginationOptions);
      return new DefaultConnectionCursor(
          Base64.getEncoder().encodeToString(jsonStr.getBytes(StandardCharsets.UTF_8)));
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
      var jsonStr = new String(Base64.getDecoder().decode(cursor));
      return jacksonObjectMapper.readValue(jsonStr, MediaPaginationOptions.class).toBuilder()
          .paginationOptions(options)
          .build();

    } catch (Exception ex) {
      var msg =
          "Could not decode cursor '"
              + cursor
              + "' into "
              + MediaPaginationOptions.class.getSimpleName();
      throw new InvalidCursorException(msg);
    }
  }
}
