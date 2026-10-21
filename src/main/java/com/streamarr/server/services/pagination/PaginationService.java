package com.streamarr.server.services.pagination;

import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class PaginationService {

  private static final int MAX_PAGE_SIZE = 500;

  public PaginationOptions getPaginationOptions(int first, String after, int last, String before) {
    var cursor = getCursor(after, before);
    var direction = getDirection(after, before, last);
    var limit = getLimit(first, last, direction);

    return PaginationOptions.builder()
        .cursor(cursor)
        .paginationDirection(direction)
        .limit(limit)
        .build();
  }

  private PaginationDirection getDirection(String after, String before, int last) {

    var afterIsBlank = StringUtils.isBlank(after);
    var beforeIsBlank = StringUtils.isBlank(before);

    if (!afterIsBlank && !beforeIsBlank) {
      throw new InvalidPaginationArgumentException(
          "Cannot request with both after and before simultaneously.");
    }

    if (!afterIsBlank) {
      return PaginationDirection.FORWARD;
    }

    return !beforeIsBlank || last > 0 ? PaginationDirection.REVERSE : PaginationDirection.FORWARD;
  }

  private Optional<String> getCursor(String after, String before) {

    var afterIsBlank = StringUtils.isBlank(after);
    var beforeIsBlank = StringUtils.isBlank(before);

    if (afterIsBlank && beforeIsBlank) {
      return Optional.empty();
    }

    return afterIsBlank ? Optional.of(before) : Optional.of(after);
  }

  private int getLimit(int first, int last, PaginationDirection direction) {
    return direction == PaginationDirection.REVERSE
        ? validateNonNegativeAndWithinMax("last", last)
        : validateNonNegativeAndWithinMax("first", first);
  }

  private int validateNonNegativeAndWithinMax(String fieldName, int pageSize) {

    if (pageSize < 0) {
      throw new InvalidPaginationArgumentException(fieldName + " must not be negative.");
    }

    if (pageSize > MAX_PAGE_SIZE) {
      throw new InvalidPaginationArgumentException(
          fieldName + " must not exceed " + MAX_PAGE_SIZE + ".");
    }

    return pageSize;
  }

  /** Checks the opposite boundary when a missing or moved media anchor cannot establish it. */
}
