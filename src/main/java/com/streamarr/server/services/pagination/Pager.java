package com.streamarr.server.services.pagination;

import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import java.util.List;
import java.util.Optional;

/**
 * Reads one page of any {@link PagedList}. A page reads one row past its limit to learn whether
 * more follow, and a request that continues from a position reads one row the other way to learn
 * whether rows precede it. The position need not still hold a row: a removed item's position still
 * separates the rows before it from the rows after it.
 */
public final class Pager {

  private Pager() {}

  /**
   * @throws InvalidPaginationCursorException when the request's position cannot belong to the list
   */
  public static <T, S> ListPage<T> page(PagedList<T, S> list, S scope, PageRequest request) {
    var order = list.order(scope);
    if (request.position().filter(position -> !order.admits(position.value())).isPresent()) {
      throw new InvalidPaginationCursorException("Cursor does not belong to this list.");
    }

    var read =
        list.rowsBeyond(
            scope,
            Seek.builder()
                .order(order)
                .position(request.position())
                .direction(request.direction())
                .limit(request.limit() + 2)
                .includingPosition(true)
                .build());
    var positionRowRead =
        !read.isEmpty() && request.position().equals(Optional.of(read.getFirst().position()));
    var beyond = positionRowRead ? read.subList(1, read.size()) : read;
    var hasMoreAhead = beyond.size() > request.limit();
    var nearest = beyond.subList(0, Math.min(beyond.size(), request.limit()));
    var hasRowsBehind = positionRowRead || hasRowsBehind(list, scope, order, request);
    if (request.direction() == PaginationDirection.FORWARD) {
      return new ListPage<>(nearest, hasMoreAhead, hasRowsBehind);
    }

    return new ListPage<>(List.copyOf(nearest.reversed()), hasRowsBehind, hasMoreAhead);
  }

  private static <T, S> boolean hasRowsBehind(
      PagedList<T, S> list, S scope, ListOrder order, PageRequest request) {
    if (request.position().isEmpty()) {
      return false;
    }

    var behind =
        Seek.builder()
            .order(order)
            .position(request.position())
            .direction(opposite(request.direction()))
            .limit(1)
            .build();
    return !list.rowsBeyond(scope, behind).isEmpty();
  }

  private static PaginationDirection opposite(PaginationDirection direction) {
    return switch (direction) {
      case FORWARD -> PaginationDirection.REVERSE;
      case REVERSE -> PaginationDirection.FORWARD;
    };
  }
}
