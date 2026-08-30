package com.streamarr.server.fakes;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;

@UtilityClass
class FakeAuditableEntityPage {

  static <T extends BaseAuditableEntity<T>> List<T> find(
      Collection<T> values, Predicate<T> scope, KeysetPaginationOptions options) {
    var ordered =
        values.stream()
            .filter(scope)
            .sorted(
                Comparator.comparing(T::getCreatedOn)
                    .reversed()
                    .thenComparing(BaseAuditableEntity::getId))
            .toList();
    var cursorIndex =
        options
            .getCursorId()
            .map(
                cursorId ->
                    ordered.stream().map(BaseAuditableEntity::getId).toList().indexOf(cursorId))
            .orElse(-1);
    if (options.getCursorId().isPresent() && cursorIndex < 0) {
      throw new InvalidPaginationCursorException("Cursor no longer identifies an item.");
    }

    var pagination = options.getPaginationOptions();
    if (pagination.getPaginationDirection() == PaginationDirection.REVERSE) {
      var to = options.getCursorId().isPresent() ? cursorIndex + 1 : ordered.size();
      var from = Math.max(0, to - pagination.getLimit() - 2);
      return ordered.subList(from, to);
    }

    var from = options.getCursorId().isPresent() ? cursorIndex : 0;
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var to = Math.min(ordered.size(), from + pagination.getLimit() + extraRows);
    return ordered.subList(from, to);
  }
}
