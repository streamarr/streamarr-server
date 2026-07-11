package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaFilterComparator;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import org.springframework.stereotype.Component;

@Component
public class CursorValidator {

  public void validateCursorAgainstFilter(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    MediaFilterComparator.findMismatch(decodedOptions.getMediaFilter(), filter)
        .ifPresent(
            detail -> {
              throw new InvalidCursorException("Cursor filter mismatch: " + detail);
            });
  }
}
