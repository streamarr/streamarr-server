package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class PaginationService {

  private static final int MAX_PAGE_SIZE = 500;

  public PaginationOptions getPaginationOptions(int first, String after, int last, String before) {
    var cursor = getCursor(after, before);
    var direction = cursor.isEmpty() ? PaginationDirection.FORWARD : getDirection(after, before);
    var limit = getLimit(first, last, direction);

    return PaginationOptions.builder()
        .cursor(cursor)
        .paginationDirection(direction)
        .limit(limit)
        .build();
  }

  private PaginationDirection getDirection(String after, String before) {

    var afterIsBlank = StringUtils.isBlank(after);
    var beforeIsBlank = StringUtils.isBlank(before);

    if (!afterIsBlank && !beforeIsBlank) {
      throw new InvalidPaginationArgumentException(
          "Cannot request with both after and before simultaneously.");
    }

    return afterIsBlank ? PaginationDirection.REVERSE : PaginationDirection.FORWARD;
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

  public <T extends BaseAuditableEntity<?>> MediaPage<T> buildMediaPage(
      List<PageItem<T>> items, PaginationOptions options, Optional<UUID> cursorId) {

    if (items.isEmpty()) {
      return emptyMediaPage();
    }

    var limit = options.getLimit();
    var direction = options.getPaginationDirection();

    var hasPreviousPage = false;
    var hasNextPage = false;

    if (cursorId.isPresent() && direction == PaginationDirection.FORWARD) {
      var cursorFound = items.getFirst().item().getId().equals(cursorId.get());
      hasPreviousPage = cursorFound;
      if (cursorFound) {
        items = items.subList(1, items.size());
      }
    }

    if (cursorId.isPresent() && direction == PaginationDirection.REVERSE) {
      var cursorFound = items.getLast().item().getId().equals(cursorId.get());
      hasNextPage = cursorFound;
      if (cursorFound) {
        items = items.subList(0, items.size() - 1);
      }
    }

    if (items.isEmpty()) {
      return new MediaPage<>(List.of(), hasNextPage, hasPreviousPage);
    }

    var isListLargerThanLimit = items.size() > limit;

    if (direction == PaginationDirection.FORWARD) {
      hasNextPage = isListLargerThanLimit;
    } else {
      hasPreviousPage = isListLargerThanLimit;
    }

    items = pruneListByLimitGivenDirection(items, limit, direction);

    return new MediaPage<>(items, hasNextPage, hasPreviousPage);
  }

  private <T> MediaPage<T> emptyMediaPage() {
    return new MediaPage<>(List.of(), false, false);
  }

  private <T> List<T> pruneListByLimitGivenDirection(
      List<T> list, int limit, PaginationDirection direction) {
    if (list.size() <= limit) {
      return list;
    }

    if (direction == PaginationDirection.REVERSE) {
      return list.subList(list.size() - limit, list.size());
    }

    return list.subList(0, limit);
  }
}
