package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaFilterComparator;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

@Component
public class CursorValidator {

  public void validateContinueWatchingCursor(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    validateCursorAgainstFilter(decodedOptions, filter);
    var timestamp = decodedOptions.getMediaFilter().getPreviousSortFieldValue();
    if (decodedOptions.getCursorId().isEmpty() || timestamp == null) {
      throw new InvalidCursorException(
          "Continue Watching cursors require an item ID and timestamp.");
    }

    try {
      Instant.parse(timestamp.toString());
    } catch (DateTimeParseException _) {
      throw new InvalidCursorException("Continue Watching cursor timestamp is invalid.");
    }
  }

  public void validateCursorAgainstFilter(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    MediaFilterComparator.findMismatch(decodedOptions.getMediaFilter(), filter)
        .ifPresent(
            detail -> {
              throw new InvalidCursorException("Cursor filter mismatch: " + detail);
            });
  }
}
