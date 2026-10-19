package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaFilterComparator;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

@Component
public class CursorValidator {
  private static final Instant EARLIEST_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant LATEST_TIMESTAMP = Instant.parse("9999-12-31T23:59:59.999999Z");

  public void validateContinueWatchingCursor(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    validateCursorAgainstFilter(decodedOptions, filter);
    var timestamp = decodedOptions.getMediaFilter().getPreviousSortFieldValue();
    if (decodedOptions.getCursorId().isEmpty() || timestamp == null) {
      throw new InvalidCursorException(
          "Continue Watching cursors require an item ID and timestamp.");
    }

    if (!isStorableTimestamp(timestamp.toString())) {
      throw new InvalidCursorException("Continue Watching cursor timestamp is invalid.");
    }
  }

  // Instant spans years PostgreSQL timestamptz cannot store; progress times have four-digit years.
  private static boolean isStorableTimestamp(String value) {
    try {
      var instant = Instant.parse(value);
      return !instant.isBefore(EARLIEST_TIMESTAMP) && !instant.isAfter(LATEST_TIMESTAMP);
    } catch (DateTimeParseException _) {
      return false;
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
